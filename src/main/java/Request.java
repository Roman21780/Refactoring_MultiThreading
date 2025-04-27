import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.util.Streams;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class Request {
    private final String method;
    private final String path;
    private final String rawPath;
    private final Map<String, String> headers;
    private final byte[] bodyBytes;

    // Для query параметров
    private Map<String, List<String>> queryParams;
    private List<NameValuePair> queryParamsList;

    // Для POST параметров
    private Map<String, List<String>> postParams;
    private List<NameValuePair> postParamsList;
    private boolean postParamsParsed = false;

    // Для multipart данных
    private List<Part> parts;
    private boolean multipartParsed = false;

    public Request(String method, String path, Map<String, String> headers, InputStream body) throws IOException {
        this.method = method;
        this.rawPath = path;
        this.path = path.split("\\?", 2)[0];
        this.headers = Collections.unmodifiableMap(headers);
        this.bodyBytes = body != null ? body.readAllBytes() : new byte[0];

        // парсим query параметры в конструкторе
        parseQueryParams();
    }

    // ========== Multipart обработка ==========

    public boolean isMultipart() {
        String contentType = headers.get("content-type");
        return contentType != null && contentType.startsWith("multipart/form-data");
    }

    private void parseMultipart() {
        if (multipartParsed) return;
        multipartParsed = true;

        if (!isMultipart()) {
            parts = Collections.emptyList();
            return;
        }

        try {
            String contentType = headers.get("content-type");
            String boundary = extractBoundary(contentType);
            if (boundary == null) {
                System.err.println("Could not extract boundary from Content-Type");
                parts = Collections.emptyList();
                return;
            }

            System.out.println("Найдена граница (boundary): " + boundary);
            boundary = "--" + boundary;

            // Используем исправленный алгоритм для разбора multipart
            parts = parseMultipartData(boundary);
        } catch (Exception e) {
            System.err.println("Multipart parse error: " + e.getMessage());
            e.printStackTrace(); // Добавим стек вызовов для лучшей диагностики
            parts = Collections.emptyList();
        }
    }

    private List<Part> parseMultipartData(String boundary) {
        List<Part> resultParts = new ArrayList<>();

        try {
            // Ищем все границы в байтовом массиве (более надежный способ)
            List<Integer> boundaryPositions = findBoundaryPositions(bodyBytes, boundary.getBytes(StandardCharsets.UTF_8));

            if (boundaryPositions.size() < 2) {
                System.out.println("Не найдены позиции границ или их недостаточно");
                return resultParts;
            }

            System.out.println("Найдено " + (boundaryPositions.size() - 1) + " частей по разделителю");

            // Обрабатываем все части между границами
            for (int i = 0; i < boundaryPositions.size() - 1; i++) {
                int start = boundaryPositions.get(i);
                int end = boundaryPositions.get(i + 1);

                // Пропускаем первую часть (преамбула)
                if (i == 0) continue;

                // Проверяем, не является ли это последней границей
                if (end - start > 4) {
                    byte[] checkBytes = new byte[4];
                    System.arraycopy(bodyBytes, start, checkBytes, 0, 4);
                    String check = new String(checkBytes, StandardCharsets.UTF_8);
                    if (check.startsWith("--\r\n")) {
                        continue;  // Пропускаем финальную границу
                    }
                }

                // Ищем разделитель между заголовками и телом
                byte[] headerEnd = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
                int headerEndPos = findSequence(bodyBytes, headerEnd, start, end);

                if (headerEndPos == -1) {
                    System.out.println("Не найден разделитель заголовков для части " + i);
                    continue;
                }

                // Извлекаем и парсим заголовки
                byte[] headersBytes = Arrays.copyOfRange(bodyBytes, start, headerEndPos);
                String headersText = new String(headersBytes, StandardCharsets.UTF_8);

                // Пропускаем начальные символы новой строки, если они есть
                if (headersText.startsWith("\r\n")) {
                    headersText = headersText.substring(2);
                }

                Map<String, String> partHeaders = new HashMap<>();
                String[] headerLines = headersText.split("\r\n");

                for (String header : headerLines) {
                    int colonIndex = header.indexOf(':');
                    if (colonIndex > 0) {
                        String name = header.substring(0, colonIndex).trim().toLowerCase();
                        String value = header.substring(colonIndex + 1).trim();
                        partHeaders.put(name, value);
                        System.out.println("Заголовок части: " + name + " = " + value);
                    }
                }

                // Извлекаем информацию из заголовков
                String contentDisposition = partHeaders.get("content-disposition");
                if (contentDisposition == null) {
                    System.out.println("Content-Disposition отсутствует для части " + i);
                    continue;
                }

                String fieldName = extractFieldName(contentDisposition);
                String fileName = extractFileName(contentDisposition);
                boolean isFile = (fileName != null && !fileName.isEmpty());
                String contentType = partHeaders.get("content-type");

                System.out.println("Разбор части: fieldName=" + fieldName + ", fileName=" + fileName + ", isFile=" + isFile);

                // Извлекаем тело части (контент) напрямую из байтов
                // +4 для пропуска \r\n\r\n
                int contentStart = headerEndPos + 4;
                int contentEnd = end - 2; // -2 для пропуска \r\n в конце

                if (contentEnd > contentStart) {
                    byte[] content = Arrays.copyOfRange(bodyBytes, contentStart, contentEnd);

                    resultParts.add(new Part(fieldName, fileName, contentType, content, isFile));
                } else {
                    System.out.println("Пустой контент для части " + i);
                    resultParts.add(new Part(fieldName, fileName, contentType, new byte[0], isFile));
                }
            }

        } catch (Exception e) {
            System.err.println("Ошибка при разборе multipart данных: " + e.getMessage());
            e.printStackTrace();
        }

        return resultParts;
    }

    // Вспомогательный метод для поиска всех позиций границ
    private List<Integer> findBoundaryPositions(byte[] data, byte[] boundary) {
        List<Integer> positions = new ArrayList<>();

        // Ищем все вхождения boundary в data
        for (int i = 0; i <= data.length - boundary.length; i++) {
            boolean found = true;
            for (int j = 0; j < boundary.length; j++) {
                if (data[i + j] != boundary[j]) {
                    found = false;
                    break;
                }
            }

            if (found) {
                positions.add(i);
                // Перескакиваем текущее совпадение
                i += boundary.length - 1;
            }
        }

        return positions;
    }

    // Вспомогательный метод для поиска последовательности байтов
    private int findSequence(byte[] data, byte[] seq, int startPos, int endPos) {
        for (int i = startPos; i <= endPos - seq.length; i++) {
            boolean found = true;
            for (int j = 0; j < seq.length; j++) {
                if (data[i + j] != seq[j]) {
                    found = false;
                    break;
                }
            }

            if (found) {
                return i;
            }
        }

        return -1;
    }

    private String extractBoundary(String contentType) {
        int boundaryIndex = contentType.indexOf("boundary=");
        if (boundaryIndex == -1) return null;

        String boundary = contentType.substring(boundaryIndex + "boundary=".length());
        if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
            boundary = boundary.substring(1, boundary.length() - 1);
        } else {
            // Если нет кавычек, отрезаем до следующего разделителя (если есть)
            int endIndex = boundary.indexOf(';');
            if (endIndex > 0) {
                boundary = boundary.substring(0, endIndex);
            }
        }

        // Удаляем пробелы, если они есть
        return boundary.trim();
    }

    private byte[] getBoundaryBytes(String contentType) {
        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            return null;
        }

        // Добавляем префикс к границе
        return ("--" + boundary).getBytes(StandardCharsets.UTF_8);
    }

    private String extractFieldName(String contentDisposition) {
        int nameIndex = contentDisposition.indexOf("name=");
        if (nameIndex == -1) return null;

        String name = contentDisposition.substring(nameIndex + "name=".length());
        if (name.startsWith("\"")) {
            int endQuote = name.indexOf("\"", 1);
            if (endQuote != -1) {
                name = name.substring(1, endQuote);
            }
        } else {
            int endPos = name.indexOf(";");
            if (endPos != -1) {
                name = name.substring(0, endPos);
            }
        }

        return name;
    }

    private String extractFileName(String contentDisposition) {
        int fileNameIndex = contentDisposition.indexOf("filename=");
        if (fileNameIndex == -1) return null;

        String fileName = contentDisposition.substring(fileNameIndex + "filename=".length());
        if (fileName.startsWith("\"")) {
            int endQuote = fileName.indexOf("\"", 1);
            if (endQuote != -1) {
                fileName = fileName.substring(1, endQuote);
            }
        } else {
            int endPos = fileName.indexOf(";");
            if (endPos != -1) {
                fileName = fileName.substring(0, endPos);
            }
        }

        return fileName.isEmpty() ? null : fileName;
    }

    public Part getPart(String name) {
        parseMultipart();
        return parts.stream()
                .filter(part -> part.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public List<Part> getParts() {
        parseMultipart();
        return parts;
    }

    public List<Part> getParts(String name) {
        parseMultipart();
        return parts.stream()
                .filter(part -> part.getName().equals(name))
                .collect(Collectors.toList());
    }

    // ========== Остальные методы ==========

    public InputStream getBody() {
        return new ByteArrayInputStream(bodyBytes);
    }

    public String getBodyAsString() {
        return new String(bodyBytes, StandardCharsets.UTF_8);
    }

    // Методы для работы с POST параметрами (x-www-form-urlencoded)
    public boolean isFormUrlEncoded() {
        String contentType = headers.get("content-type");
        return contentType != null && contentType.contains("x-www-form-urlencoded");
    }

    private void parsePostParamsIfNeeded() {
        if (postParamsParsed) return;
        postParamsParsed = true;

        if (!("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) || !isFormUrlEncoded()) {
            postParamsList = Collections.emptyList();
            postParams = Collections.emptyMap();
            return;
        }

        try {
            String bodyStr = getBodyAsString();
            postParamsList = URLEncodedUtils.parse(bodyStr, StandardCharsets.UTF_8);

            postParams = new HashMap<>();
            for (NameValuePair pair : postParamsList) {
                postParams.computeIfAbsent(pair.getName(), k -> new ArrayList<>())
                        .add(pair.getValue());
            }

            postParams.replaceAll((k, v) -> Collections.unmodifiableList(v));
            postParams = Collections.unmodifiableMap(postParams);
            postParamsList = Collections.unmodifiableList(postParamsList);
        } catch (Exception e) {
            System.err.println("Error parsing POST parameters: " + e.getMessage());
            postParamsList = Collections.emptyList();
            postParams = Collections.emptyMap();
        }
    }

    public Map<String, List<String>> getPostParams() {
        parsePostParamsIfNeeded();
        return postParams;
    }

    public List<NameValuePair> getPostParamsList() {
        parsePostParamsIfNeeded();
        return postParamsList;
    }

    public String getPostParam(String name) {
        List<String> values = getPostParams().get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    public List<String> getPostParamValues(String name) {
        return getPostParams().getOrDefault(name, Collections.emptyList());
    }

    public boolean hasPostParam(String name) {
        return getPostParams().containsKey(name);
    }

    private void parseQueryParams() {
        queryParams = new HashMap<>();
        queryParamsList = new ArrayList<>();

        if (rawPath.contains("?")) {
            String query = rawPath.substring(rawPath.indexOf('?') + 1);
            try {
                List<NameValuePair> parsed = URLEncodedUtils.parse(query, StandardCharsets.UTF_8);
                for (NameValuePair pair : parsed) {
                    queryParams.computeIfAbsent(pair.getName(), k -> new ArrayList<>())
                            .add(pair.getValue());
                    queryParamsList.add(pair);
                }
            } catch (Exception e) {
                System.err.println("Error parsing query parameters: " + e.getMessage());
            }
        }

        queryParams.replaceAll((k, v) -> Collections.unmodifiableList(v));
        queryParams = Collections.unmodifiableMap(queryParams);
        queryParamsList = Collections.unmodifiableList(queryParamsList);
    }


    // Методы для работы с query параметрами
    public Map<String, List<String>> getQueryParams() {
        return queryParams;
    }

    public List<NameValuePair> getQueryParamsList() {
        return queryParamsList;
    }

    public String getQueryParam(String name) {
        List<String> values = getQueryParams().get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    public List<String> getQueryParamValues(String name) {
        return getQueryParams().getOrDefault(name, Collections.emptyList());
    }

    public boolean hasQueryParam(String name) {
        return getQueryParams().containsKey(name);
    }

    // Базовые геттеры
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getRawPath() { return rawPath; }
    public Map<String, String> getHeaders() { return headers; }

    @Override
    public String toString() {
        return method + " " + rawPath;
    }

    // Класс Part для работы с частями multipart запроса
    public static class Part {
        private final String name;
        private final String fileName;
        private final String contentType;
        private final byte[] content;
        private final boolean isFile;
        private Map<String, String> headers;

        public Part(String name, String fileName, String contentType, byte[] content, boolean isFile) {
            this.name = name;
            this.fileName = fileName;
            this.contentType = contentType;
            this.content = content;
            this.isFile = isFile;
            this.headers = new HashMap<>();
            if (contentType != null) {
                headers.put("content-type", contentType);
            }
        }

        public String getName() { return name; }
        public String getFileName() { return fileName; }
        public String getContentType() { return contentType; }
        public boolean isFile() { return isFile; }
        public long getSize() { return content.length; }
        public Map<String, String> getHeaders() { return headers; }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        public String getString() {
            return new String(content, StandardCharsets.UTF_8);
        }

        public byte[] getBytes() {
            return content.clone();
        }
    }
}