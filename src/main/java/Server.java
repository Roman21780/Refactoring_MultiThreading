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

    // Увеличены таймауты для более стабильной работы
    private static final int SO_TIMEOUT = 60000; // 60 секунд для основного таймаута
    private static final int HEADER_READ_TIMEOUT = 5000; // 15 секунд для чтения заголовков
    private static final int BODY_READ_TIMEOUT = 30000; // 30 секунд для чтения тела

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
        try (socket;
             InputStream input = socket.getInputStream();
             OutputStream output = socket.getOutputStream()) {

            // Отключаем таймаут для первоначального чтения
            socket.setSoTimeout(0);

            Request request = parseRequest(input, socket);
            if (request == null) {
                sendResponse(output, 400, "Bad Request");
                return;
            }

            // Обработка запроса
            Handler handler = findHandler(request.getMethod(), request.getPath());
            if (handler != null) {
                handler.handle(request, output);
            } else {
                sendResponse(output, 404, "Not Found");
            }

        } catch (SocketTimeoutException e) {
            System.err.println("Timeout: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("IO Error: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Request parseRequest(InputStream inputStream, Socket socket) throws IOException {
        // Используем BufferedInputStream для надежного чтения
        BufferedInputStream bufferedInput = new BufferedInputStream(inputStream);
        bufferedInput.mark(Integer.MAX_VALUE); // Помечаем начало для возможного reset()

        // Читаем первую строку без таймаута
        String requestLine = readLine(bufferedInput);
        if (requestLine == null || requestLine.isEmpty()) {
            System.out.println("Empty request line");
            return null;
        }

        System.out.println("Raw request line: " + requestLine);
        String[] parts = requestLine.split(" ");
        if (parts.length != 3) {
            return null;
        }

        String method = parts[0].toUpperCase();
        String path = parts[1];
        Map<String, String> headers = new HashMap<>();

        // Чтение заголовков с таймаутом
        socket.setSoTimeout(HEADER_READ_TIMEOUT);
        String headerLine;
        while (!(headerLine = readLine(bufferedInput)).isEmpty()) {
            int colonPos = headerLine.indexOf(':');
            if (colonPos > 0) {
                String key = headerLine.substring(0, colonPos).trim().toLowerCase();
                String value = headerLine.substring(colonPos + 1).trim();
                headers.put(key, value);
            }
        }

        // Подготовка тела запроса
        InputStream bodyStream = null;
        int contentLength = 0;

        try {
            contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException e) {
            System.err.println("Invalid Content-Length");
        }

        if (contentLength > 0) {
            // Чтение тела без таймаута (уже считали заголовки)
            socket.setSoTimeout(0);
            byte[] bodyBytes = new byte[contentLength];
            int bytesRead = 0;

            while (bytesRead < contentLength) {
                int read = bufferedInput.read(bodyBytes, bytesRead, contentLength - bytesRead);
                if (read == -1) break;
                bytesRead += read;
            }

            bodyStream = new ByteArrayInputStream(bodyBytes);
            System.out.println("Read body: " + bytesRead + " bytes");
        }

        return new Request(method, path, headers, bodyStream);
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    // Улучшенный метод для точного чтения тела запроса заданной длины
    private InputStream extractExactBodyContent(InputStream input, int contentLength) throws IOException {
        System.out.println("Trying to read " + contentLength + " bytes from request body");

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[Math.min(contentLength, 4096)];
        int totalBytesRead = 0;

        while (totalBytesRead < contentLength) {
            int bytesToRead = Math.min(data.length, contentLength - totalBytesRead);
            int bytesRead = input.read(data, 0, bytesToRead);

            if (bytesRead == -1) {
                System.out.println("End of stream reached after " + totalBytesRead + " bytes");
                break;
            }

            buffer.write(data, 0, bytesRead);
            totalBytesRead += bytesRead;
            System.out.println("Read " + bytesRead + " bytes, total: " + totalBytesRead + "/" + contentLength);
        }

        byte[] bodyContent = buffer.toByteArray();
        System.out.println("Body size: " + bodyContent.length + " bytes read");

        if (bodyContent.length > 0) {
            String preview = new String(bodyContent, 0, Math.min(bodyContent.length, 100), StandardCharsets.UTF_8);
            System.out.println("Body preview: " + preview + (bodyContent.length > 100 ? "..." : ""));
        }

        return new ByteArrayInputStream(bodyContent);
    }

    private void handleOptionsRequest(BufferedOutputStream out, String path) throws IOException {
        String basePath = path.split("\\?", 2)[0];

        String allowedMethods = handlers.entrySet().stream()
                .filter(e -> e.getValue().containsKey(basePath))
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));

        // Добавляем OPTIONS к списку разрешенных методов
        if (!allowedMethods.isEmpty()) {
            allowedMethods += ", OPTIONS";
        } else {
            allowedMethods = "OPTIONS";
        }

        String response = "HTTP/1.1 200 OK\r\n" +
                "Allow: " + allowedMethods + "\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static void sendResponse(OutputStream out, int statusCode, String body) throws IOException {
        sendResponse(out, statusCode, "text/plain", body);
    }

    public static void sendResponse(OutputStream out, int statusCode, String contentType, String body) throws IOException {
        String statusText = getStatusText(statusCode);

        // Проверка на null тело
        if (body == null) {
            body = "";
        }

        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        String response = String.format(
                "HTTP/1.1 %d %s\r\n" +
                        "Content-Type: %s; charset=utf-8\r\n" +
                        "Content-Length: %d\r\n" +
                        "Connection: close\r\n\r\n",
                statusCode, statusText, contentType, bodyBytes.length
        );

        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
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
            case 408: return "Request Timeout";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 500: return "Internal Server Error";
            default: return "Unknown Status";
        }
    }

    private Handler findHandler(String method, String path) {
        Map<String, Handler> methodHandlers = handlers.get(method);
        if (methodHandlers == null) return null;

        // Извлекаем базовый путь без параметров запроса
        String basePath = path.split("\\?", 2)[0];
        return methodHandlers.get(basePath);
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