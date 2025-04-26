import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class Server {
    private final Map<String, Map<String, Handler>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService threadPool;
    private volatile boolean isRunning;
    private static final int SO_TIMEOUT = 5000;
    private static final int HEADER_READ_TIMEOUT = 1000;

    public Server() {
        this(Runtime.getRuntime().availableProcessors() * 2);
    }

    public Server(int threadPoolSize) {
        this.threadPool = Executors.newFixedThreadPool(threadPoolSize);
        this.isRunning = true;
    }

    public void addHandler(String method, String path, Handler handler) {
        handlers.computeIfAbsent(method.toUpperCase(), k -> new ConcurrentHashMap<>())
                .put(path, handler);
    }

    public void listen(int port) throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            System.out.println(String.format("Server started on port %d (Threads: %d)",
                    port, ((ThreadPoolExecutor)threadPool).getMaximumPoolSize()));

            while (isRunning) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setSoTimeout(SO_TIMEOUT);
                    threadPool.execute(() -> handleConnection(socket));
                } catch (SocketException e) {
                    if (isRunning) {
                        System.err.println("Socket error: " + e.getMessage());
                    }
                }
            }
        } finally {
            shutdown();
        }
    }

    private void handleConnection(Socket socket) {
        String clientAddress = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        System.out.println("New connection from " + clientAddress);

        try (socket;
             InputStream input = socket.getInputStream();
             BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {

            // Устанавливаем таймаут для чтения заголовков
            socket.setSoTimeout(HEADER_READ_TIMEOUT);

            // Парсим запрос
            Request request = parseRequest(input);
            if (request == null) {
                System.out.println("Invalid request from " + clientAddress);
                sendResponse(output, 400, "Bad Request");
                return;
            }

            // Возвращаем обычный таймаут
            socket.setSoTimeout(SO_TIMEOUT);

            // Логируем базовую информацию о запросе
            System.out.printf("%s %s from %s%n",
                    request.getMethod(), request.getPath(), clientAddress);

            // Автоматические ответы для служебных запросов
            if ("/favicon.ico".equals(request.getPath())) {
                sendResponse(output, 204, "");
                return;
            }

            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                handleOptionsRequest(output);
                return;
            }

            // Ищем обработчик для запроса
            Handler handler = findHandler(request.getMethod(), request.getPath());
            if (handler == null) {
                System.out.println("No handler for " + request.getMethod() + " " + request.getPath());
                sendResponse(output, 404, "Not Found");
                return;
            }

            // Вызываем обработчик
            try {
                handler.handle(request, output);
                System.out.println("Request processed successfully");
            } catch (Exception e) {
                System.err.println("Handler error: " + e.getMessage());
                sendResponse(output, 500, "Internal Server Error");
            }

        } catch (SocketTimeoutException e) {
            System.out.println("Timeout with client " + clientAddress + ": " + e.getMessage());
        } catch (IOException e) {
            System.err.println("IO error with client " + clientAddress + ": " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Unexpected error with client " + clientAddress + ": " + e.getMessage());
        } finally {
            System.out.println("Connection closed with " + clientAddress);
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

        String method = parts[0].toUpperCase();
        String path = parts[1];
        Map<String, String> headers = new HashMap<>();

        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                headers.put(
                        line.substring(0, separator).trim(),
                        line.substring(separator + 1).trim()
                );
            }
        }

        InputStream body = null;
        if (headers.containsKey("Content-Length")) {
            try {
                int contentLength = Integer.parseInt(headers.get("Content-Length"));
                if (contentLength > 0) {
                    body = readRequestBody(inputStream, contentLength);
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid Content-Length header");
            }
        }

        return new Request(method, path, headers, body);
    }

    private InputStream readRequestBody(InputStream input, int contentLength) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[Math.min(contentLength, 8192)];
        int remaining = contentLength;

        while (remaining > 0) {
            int read = input.read(data, 0, Math.min(data.length, remaining));
            if (read == -1) break;
            buffer.write(data, 0, read);
            remaining -= read;
        }

        return new ByteArrayInputStream(buffer.toByteArray());
    }

    private void handleOptionsRequest(BufferedOutputStream out) throws IOException {
        String allowedMethods = String.join(", ", handlers.keySet());
        String response = "HTTP/1.1 200 OK\r\n" +
                "Allow: " + allowedMethods + "\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static void sendResponse(OutputStream out, int statusCode, String body) throws IOException {
        String statusText = getStatusText(statusCode);
        String response = String.format(
                "HTTP/1.1 %d %s\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: %d\r\n" +
                        "Connection: close\r\n\r\n%s",
                statusCode, statusText, body.length(), body
        );
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String getStatusText(int statusCode) {
        switch (statusCode) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            default: return "";
        }
    }

    private Handler findHandler(String method, String path) {
        Map<String, Handler> methodHandlers = handlers.get(method);
        if (methodHandlers == null) return null;
        return methodHandlers.get(path.split("\\?", 2)[0]);
    }

    private void logRequest(Request request) {
        System.out.println(request.getMethod() + " " + request.getPath());
        for (Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
            System.out.println("  " + entry.getKey() + ": " + entry.getValue());
        }
    }

    public void shutdown() {
        isRunning = false;
        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("Server stopped");
    }
}
