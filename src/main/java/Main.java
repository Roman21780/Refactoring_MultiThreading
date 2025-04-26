import org.apache.http.NameValuePair;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
        server.addHandler("GET", "/data", (req, out) -> {
            String response = "GET /data response\n" +
                    "Query params: " + req.getQueryParams();
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

        // Универсальный POST обработчик для /data
        server.addHandler("POST", "/data", (req, out) -> {
            try {
                // Читаем Content-Type
                String contentType = req.getHeaders().getOrDefault("Content-Type", "text/plain");

                // Читаем тело запроса
                String bodyContent;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(req.getBody(), StandardCharsets.UTF_8))) {
                    bodyContent = reader.lines().collect(Collectors.joining("\n"));
                }

                // Формируем ответ в зависимости от Content-Type
                String response;
                if (contentType.contains("application/json")) {
                    response = "JSON received: " + bodyContent;
                } else {
                    response = "Text received: " + bodyContent;
                }

                Server.sendResponse(out, 200, response);

            } catch (Exception e) {
                System.err.println("Error processing POST request: " + e.getMessage());
                Server.sendResponse(out, 500, "Error processing request");
            }
        });

        server.listen(9999);
    }
}