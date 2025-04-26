import org.apache.http.NameValuePair;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) {
        final var server = new Server();

        // Простой обработчик для тестирования
        server.addHandler("GET", "/", (request, out) -> {
            Server.sendResponse(out, 200, "Server is working!");
        });

        // Обработчик для /messages
        server.addHandler("GET", "/messages", (request, out) -> {
            String response = "Messages endpoint response";
            Server.sendResponse(out, 200, response);
        });

        // GET /messages
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

        // GET /duplicate (обработка дублирующихся параметров)
        server.addHandler("GET", "/duplicate", (request, out) -> {
            List<NameValuePair> paramsList = request.getQueryParamsList();
            String responseBody = paramsList.stream()
                    .map(p -> p.getName() + "=" + p.getValue())
                    .collect(Collectors.joining("\n"));

            Server.sendResponse(out, 200, responseBody);
        });

        // POST /messages
        server.addHandler("POST", "/messages", (request, out) -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getBody()))) {
                String bodyContent = reader.lines().collect(Collectors.joining("\n"));
                String responseBody = "POST messages. Body: " + bodyContent;

                Server.sendResponse(out, 200, responseBody);
            } catch (Exception e) {
                System.err.println("Error reading POST body: " + e.getMessage());
                Server.sendResponse(out, 500, "Error reading body");
            }
        });

        server.listen(9999);
    }
}