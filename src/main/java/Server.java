import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

public class Server {
    private final Map<String, Map<String, Handler>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService threadPool = Executors.newFixedThreadPool(64);

    public void addHandler(String method, String path, Handler handler) {
        handlers.computeIfAbsent(method, k -> new ConcurrentHashMap<>()).put(path, handler);
    }

    public void listen(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server started on port " + port);

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setSoTimeout(5000); // 5 секунд таймаут
                    threadPool.execute(() -> handleConnection(socket));
                } catch (IOException e) {
                    System.err.println("Accept error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        } finally {
            threadPool.shutdown();
        }
    }

    private void handleConnection(Socket socket) {
        try (socket;
             InputStream input = socket.getInputStream();
             BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {

            Request request = parseRequest(input);
            if (request == null) {
                sendResponse(output, 400, "Bad Request");
                return;
            }

            // Обработка favicon.ico
            if ("/favicon.ico".equals(request.getPath())) {
                sendResponse(output, 404, "Not Found");
                return;
            }

            Handler handler = findHandler(request.getMethod(), request.getPath());
            if (handler == null) {
                sendResponse(output, 404, "No handler for " + request.getPath());
                return;
            }

            handler.handle(request, output);

        } catch (SocketException e) {
            System.out.println("Client disconnected: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        }
    }

    private Request parseRequest(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }

        String[] parts = requestLine.split(" ");
        if (parts.length != 3) {
            return null;
        }

        String method = parts[0];
        String path = parts[1];
        String protocol = parts[2];

        // Читаем заголовки
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                String headerName = line.substring(0, separator).trim();
                String headerValue = line.substring(separator + 1).trim();
                headers.put(headerName, headerValue);
            }
        }

        // Читаем тело запроса, если есть
        InputStream body = null;
        if (headers.containsKey("Content-Length")) {
            int contentLength = Integer.parseInt(headers.get("Content-Length"));
            if (contentLength > 0) {
                byte[] bodyBytes = new byte[contentLength];
                int bytesRead = 0;
                while (bytesRead < contentLength) {
                    int result = inputStream.read(bodyBytes, bytesRead, contentLength - bytesRead);
                    if (result == -1) break;
                    bytesRead += result;
                }
                body = new ByteArrayInputStream(bodyBytes);
            }
        }

        return new Request(method, path, headers, body);
    }

    private Handler findHandler(String method, String path) {
        Map<String, Handler> methodHandlers = handlers.get(method);
        if (methodHandlers == null) return null;
        return methodHandlers.get(path.split("\\?")[0]);
    }

    public static void sendResponse(OutputStream out, int statusCode, String body) throws IOException {
        String statusText = getStatusText(statusCode);
        String response = String.format(
                "HTTP/1.1 %d %s\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: %d\r\n" +
                        "Connection: close\r\n" +
                        "\r\n" +
                        "%s",
                statusCode, statusText, body.length(), body
        );

        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String getStatusText(int statusCode) {
        return switch (statusCode) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default -> "Unknown";
        };
    }

    // Остальные методы остаются без изменений
}