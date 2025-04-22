import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {
    private final Map<String, Map<String, Handler>> handlers = new HashMap<>();
    private final ExecutorService threadPool = Executors.newFixedThreadPool(64);

    public void addHandler(String method, String path, Handler handler) {
        handlers.computeIfAbsent(method, _ -> new HashMap<>()).put(path, handler);
    }

    public void listen(int port) {
        try (final var serverSocket = new ServerSocket(port)) {
            while (true) {
                final var socket = serverSocket.accept();
                threadPool.submit(() -> handleConnection(socket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            threadPool.shutdown();
        }
    }

    private void handleConnection(Socket socket) {
        try (
                socket;
                final var in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                final var out = new BufferedOutputStream(socket.getOutputStream());
        ) {
            final var request = parseRequest(in);
            if (request == null) return;

            var methodHandlers = handlers.get(request.getMethod());
            if (methodHandlers == null) {
                sendNotFound(out);
                return;
            }

            var handler = methodHandlers.get(request.getPath());
            if (handler == null) {
                sendNotFound(out);
                return;
            }

            handler.handle(request, out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Request parseRequest(BufferedReader in) throws IOException {
        final var requestLine = in.readLine();
        if (requestLine == null) return null;

        final var parts = requestLine.split(" ");
        if (parts.length != 3) return null;

        final var method = parts[0];
        var pathAndQuery = parts[1].split("\\?");
        final var path = pathAndQuery[0];

        // Парсим query параметры
        Map<String, String> queryParams = new HashMap<>();
        if (pathAndQuery.length > 1) {
            Arrays.stream(pathAndQuery[1].split("&"))
                    .map(param -> param.split("="))
                    .filter(pair -> pair.length == 2)
                    .forEach(pair -> queryParams.put(pair[0], pair[1]));
        }

        // Читаем заголовки
        var headers = new HashMap<String, String>();
        String line;
        while (!(line = in.readLine()).isEmpty()) {
            var headerParts = line.split(": ");
            if (headerParts.length == 2) {
                headers.put(headerParts[0], headerParts[1]);
            }
        }

        // Читаем тело если есть
        InputStream body = null;
        if (headers.containsKey("Content-Length")) {
            var contentLength = Integer.parseInt(headers.get("Content-Length"));
            if (contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                in.read(bodyChars, 0, contentLength);
                body = new ByteArrayInputStream(new String(bodyChars).getBytes());
            }
        }

        return new Request(method, path, headers, body, queryParams);
    }

    private void sendNotFound(BufferedOutputStream out) throws IOException {
        out.write((
                "HTTP/1.1 404 Not Found\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
        ).getBytes());
        out.flush();
    }
}
