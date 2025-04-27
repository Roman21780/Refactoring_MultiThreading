import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) throws IOException {
        final var server = new Server();

        // Простой обработчик для тестирования
        server.addHandler("GET", "/", (_, out) -> {
            Server.sendResponse(out, 200, "Server is working!");
        });

        // GET /messages с параметрами
        server.addHandler("GET", "/messages", (request, out) -> {
            String limit = request.getQueryParam("limit");
            String offset = request.getQueryParam("offset");

            Map<String, List<String>> allParams = request.getQueryParams();
            String paramsInfo = allParams.entrySet().stream()
                    .map(e -> e.getKey() + "=" + String.join(",", e.getValue()))
                    .collect(Collectors.joining(", "));

            String responseBody = "Query parameters: " + paramsInfo;
            if (limit != null && offset != null) {
                responseBody += "\nMessages with limit=" + limit + " and offset=" + offset;
            }

            Server.sendResponse(out, 200, responseBody);
        });

        // GET /params (демонстрация getQueryParamsList)
        server.addHandler("GET", "/params", (request, out) -> {
            List<NameValuePair> paramsList = request.getQueryParamsList();
            String responseBody = paramsList.stream()
                    .map(p -> p.getName() + "=" + p.getValue())
                    .collect(Collectors.joining("\n"));

            Server.sendResponse(out, 200, responseBody);
        });

        // GET /data
        server.addHandler("GET", "/data", (request, out) -> {
            String response = "GET /data response\n" +
                    "Query params: " + request.getQueryParams();
            Server.sendResponse(out, 200, response);
        });

        // GET /duplicate (обработка дублирующихся параметров)
        server.addHandler("GET", "/duplicate", (request, out) -> {
            List<NameValuePair> paramsList = request.getQueryParamsList();
            String responseBody = paramsList.stream()
                    .map(p -> p.getName() + "=" + p.getValue())
                    .collect(Collectors.joining("\n"));

            Server.sendResponse(out, 200, responseBody);
        });

        // Улучшенный обработчик POST-запроса для /data
        server.addHandler("POST", "/data", (req, out) -> {
            try {
                // Проверяем наличие заголовка Content-Length
                if (!req.getHeaders().containsKey("content-length")) {
                    System.out.println("Missing Content-Length header");
                    Server.sendResponse(out, 400, "Missing Content-Length header");
                    return;
                }

                // Читаем Content-Type
                String contentType = req.getHeaders().getOrDefault("content-type", "text/plain");
                System.out.println("Processing POST request with Content-Type: " + contentType);

                // Проверяем наличие тела запроса
                if (req.getBody() == null) {
                    System.out.println("Request body is missing");
                    Server.sendResponse(out, 400, "Request body is missing");
                    return;
                }

                // Безопасно получаем Content-Length
                int contentLength;
                try {
                    contentLength = Integer.parseInt(req.getHeaders().get("content-length"));
                    if (contentLength <= 0) {
                        Server.sendResponse(out, 400, "Invalid Content-Length: must be positive");
                        return;
                    }
                } catch (NumberFormatException e) {
                    Server.sendResponse(out, 400, "Invalid Content-Length format");
                    return;
                }

                // Чтение тела запроса с обработкой исключений и ограничением по времени
                byte[] bodyBytes = new byte[contentLength];
                int totalBytesRead = 0;
                long startTime = System.currentTimeMillis();
                long timeoutMillis = 10000; // 10 секунд максимум для чтения

                try (InputStream bodyStream = req.getBody()) {
                    int bytesRead;
                    while (totalBytesRead < contentLength &&
                            (System.currentTimeMillis() - startTime) < timeoutMillis) {

                        // Определяем максимальное количество байт для чтения за один раз
                        int maxToRead = Math.min(1024, contentLength - totalBytesRead);

                        bytesRead = bodyStream.read(bodyBytes, totalBytesRead, maxToRead);

                        if (bytesRead == -1) {
                            System.out.println("End of stream reached after " + totalBytesRead + " bytes");
                            break; // Конец потока
                        }

                        totalBytesRead += bytesRead;
                        System.out.println("POST handler: read " + bytesRead + " bytes, total: " + totalBytesRead);
                    }
                } catch (IOException e) {
                    System.err.println("Error reading request body: " + e.getMessage());
                    Server.sendResponse(out, 500, "Error reading request body: " + e.getMessage());
                    return;
                }

                // Проверяем, прочитали ли мы все данные
                if (totalBytesRead < contentLength) {
                    System.out.println("WARNING: Read only " + totalBytesRead + " bytes out of " + contentLength);
                    // Продолжаем с тем, что удалось прочитать
                }

                // Преобразуем содержимое в строку
                String bodyContent = new String(bodyBytes, 0, totalBytesRead, StandardCharsets.UTF_8);
                System.out.println("POST body content: " + bodyContent);

                // Формируем и отправляем ответ
                String response = "Received (" + contentType + "): " + bodyContent;
                System.out.println("Sending response: " + response);
                Server.sendResponse(out, 200, response);

            } catch (Exception e) {
                System.err.println("POST error: " + e.getMessage());
                e.printStackTrace();  // Выводим стек вызовов для отладки
                Server.sendResponse(out, 500, "Error processing request: " + e.getMessage());
            }
        });

        // Добавляем новый обработчик для тестирования чтения JSON
        server.addHandler("POST", "/json", (req, out) -> {
            try {
                // Проверяем Content-Type
                String contentType = req.getHeaders().getOrDefault("content-type", "");
                if (!contentType.contains("application/json")) {
                    Server.sendResponse(out, 415, "Expected Content-Type: application/json");
                    return;
                }

                // Читаем тело
                if (req.getBody() == null) {
                    Server.sendResponse(out, 400, "Request body is missing");
                    return;
                }

                // Читаем JSON из тела запроса
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[4096];
                int bytesRead;
                try (InputStream bodyStream = req.getBody()) {
                    while ((bytesRead = bodyStream.read(data)) != -1) {
                        buffer.write(data, 0, bytesRead);
                    }
                }

                String jsonBody = buffer.toString(StandardCharsets.UTF_8.name());
                System.out.println("Received JSON: " + jsonBody);

                // Отправляем эхо-ответ
                String response = "{\n  \"success\": true,\n  \"message\": \"JSON received\",\n  \"data\": " +
                        jsonBody + "\n}";
                Server.sendResponse(out, 200, "application/json", response);

            } catch (Exception e) {
                System.err.println("JSON handler error: " + e.getMessage());
                e.printStackTrace();
                Server.sendResponse(out, 500, "application/json",
                        "{\n  \"success\": false,\n  \"error\": \"" + e.getMessage() + "\"\n}");
            }
        });

        server.addHandler("POST", "/form", (request, out) -> {
            try {
                // Проверка Content-Type
                if (!request.isFormUrlEncoded()) {
                    Server.sendResponse(out, 400, "Content-Type must be application/x-www-form-urlencoded");
                    return;
                }

                // Чтение тела
                String bodyStr;
                try (InputStream bodyStream = request.getBody();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(bodyStream, StandardCharsets.UTF_8))) {
                    bodyStr = reader.lines().collect(Collectors.joining("\n"));
                }

                System.out.println("Raw POST body: " + bodyStr);

                // Парсинг параметров
                List<NameValuePair> params = URLEncodedUtils.parse(bodyStr, StandardCharsets.UTF_8);
                Map<String, List<String>> paramMap = new HashMap<>();

                for (NameValuePair pair : params) {
                    paramMap.computeIfAbsent(pair.getName(), k -> new ArrayList<>())
                            .add(pair.getValue());
                }

                // Формирование ответа
                String response = "Form data:\n" +
                        "Name: " + paramMap.getOrDefault("name", List.of("null")).get(0) + "\n" +
                        "Colors: " + String.join(", ", paramMap.getOrDefault("color", List.of("none")));

                Server.sendResponse(out, 200, response);
            } catch (Exception e) {
                System.err.println("Error processing form: " + e.getMessage());
                Server.sendResponse(out, 500, "Server error: " + e.getMessage());
            }
        });

        server.addHandler("POST", "/upload", (request, out) -> {
            try {
                // Проверка multipart
                if (!request.isMultipart()) {
                    Server.sendResponse(out, 415, "Expected multipart/form-data");
                    return;
                }

                // Подготовка директории
                File uploadDir = new File("uploads");
                if (!uploadDir.exists() && !uploadDir.mkdirs()) {
                    throw new IOException("Failed to create upload directory");
                }

                // Отладочная информация о запросе
                System.out.println("Content-Type: " + request.getHeaders().getOrDefault("content-type", "не указан"));
                System.out.println("Content-Length: " + request.getHeaders().getOrDefault("content-length", "не указан"));
                System.out.println("Размер тела запроса: " + request.getBodyAsString().length() + " символов");

                // Обработка частей
                List<Map<String, Object>> items = new ArrayList<>();

                // Отладочная информация
                System.out.println("Обработка multipart запроса");
                System.out.println("Количество частей: " + request.getParts().size());

                for (Request.Part part : request.getParts()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("field", part.getName());

                    System.out.println("\n--- Обработка части: " + part.getName() + " ---");
                    System.out.println("Размер части: " + part.getSize() + " байт");
                    System.out.println("Это файл: " + part.isFile());

                    // Проверяем имя файла
                    String fileName = part.getFileName();
                    System.out.println("Имя файла: " + (fileName != null ? fileName : "null"));

                    if (fileName != null) {
                        System.out.println("Тип контента: " + part.getContentType());

                        // Сохранение файла
                        if (part.getSize() > 0) {
                            String savedName = System.currentTimeMillis() + "_" + fileName;
                            File file = new File(uploadDir, savedName);

                            try (FileOutputStream fos = new FileOutputStream(file);
                                 InputStream partStream = part.getInputStream()) {

                                byte[] buffer = new byte[8192];
                                int bytesRead;
                                long totalBytesWritten = 0;

                                while ((bytesRead = partStream.read(buffer)) != -1) {
                                    fos.write(buffer, 0, bytesRead);
                                    totalBytesWritten += bytesRead;
                                }

                                System.out.println("Файл сохранен: " + file.getAbsolutePath());
                                System.out.println("Записано байт: " + totalBytesWritten);

                                item.put("type", "file");
                                item.put("originalName", fileName);
                                item.put("savedName", savedName);
                                item.put("size", file.length());
                                item.put("contentType", part.getContentType());
                            }
                        } else {
                            System.out.println("Файл имеет нулевой размер");
                            item.put("type", "file");
                            item.put("originalName", fileName);
                            item.put("size", 0);
                            item.put("error", "Zero size file");
                        }
                    } else {
                        System.out.println("Значение поля: " + part.getString());
                        item.put("type", "field");
                        item.put("value", part.getString());
                    }
                    items.add(item);
                }

                // Формирование ответа
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "success");
                response.put("items", items);
                response.put("count", items.size());

                String jsonResponse = new ObjectMapper().writeValueAsString(response);
                Server.sendJsonResponse(out, 200, jsonResponse);

            } catch (Exception e) {
                e.printStackTrace();  // Добавляем вывод стека для отладки
                try {
                    Map<String, String> error = new LinkedHashMap<>();
                    error.put("status", "error");
                    error.put("message", e.getMessage());

                    String jsonError = new ObjectMapper().writeValueAsString(error);
                    Server.sendJsonResponse(out, 500, jsonError);
                } catch (IOException ioEx) {
                    System.err.println("Failed to send error: " + ioEx.getMessage());
                }
            }
        });

        // Запускаем сервер
        server.listen(9999);
    }
}