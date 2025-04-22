import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) {
        final var server = new Server();

        // добавление хендлеров (обработчиков)
        // GET /messages
        server.addHandler("GET", "/messages", (request, out) -> {
            // Используем заголовки из запроса
            String userAgent = request.getHeader("User-Agent");
            String responseBody = "GET messages. User-Agent: " + userAgent;

            String response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: " + responseBody.length() + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    responseBody;
            out.write(response.getBytes());
            out.flush();
        });

        // POST /messages
        server.addHandler("POST", "/messages", (request, out) -> {
            // Читаем тело запроса
            String bodyContent = new BufferedReader(new InputStreamReader(request.getBody()))
                    .lines()
                    .collect(Collectors.joining("\n"));

            String responseBody = "POST messages. Body: " + bodyContent;
            String response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: " + responseBody.length() + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    responseBody;
            out.write(response.getBytes());
            out.flush();
        });

        // GET /messages?user=123
        server.addHandler("GET", "/messages", (request, out) -> {
            String userId = request.getQueryParam("user");
            String responseBody = userId != null
                    ? "Messages for user: " + userId
                    : "All messages";

            String response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: " + responseBody.length() + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    responseBody;
            out.write(response.getBytes());
            out.flush();
        });

        server.listen(9999);
    }


}
