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
    private static final int HEADER_READ_TIMEOUT = 15000; // 15 секунд для чтения заголовков
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
        String clientAddress = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        System.out.println("New connection from " + clientAddress);

        try (socket;
             InputStream input = socket.getInputStream();
             BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {

            // Увеличенный таймаут для чтения заголовков
            socket.setSoTimeout(HEADER_READ_TIMEOUT);

            // Парсим запрос - передаем socket в качестве второго параметра
            Request request = parseRequest(input, socket);

            // Проверяем валидность запроса
            if (request == null) {
                System.out.println("Invalid request from " + clientAddress);
                sendResponse(output, 400, "Bad Request");
                return;
            }

            // Восстанавливаем обычный таймаут для последующих операций
            socket.setSoTimeout(SO_TIMEOUT);

            // Логируем запрос
            System.out.printf("%s %s from %s%n",
                    request.getMethod(), request.getPath(), clientAddress);
            System.out.println("Headers:");
            request.getHeaders().forEach((k, v) -> System.out.println("  " + k + ": " + v));

            // Обработка специальных запросов
            if ("/favicon.ico".equals(request.getPath())) {
                sendResponse(output, 204, "");
                return;
            }

            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                handleOptionsRequest(output, request.getPath());
                return;
            }

            // Поиск обработчика
            Handler handler = findHandler(request.getMethod(), request.getPath());
            if (handler == null) {
                System.out.println("No handler for " + request.getMethod() + " " + request.getPath());
                sendResponse(output, 404, "Not Found");
                return;
            }

            // Вызов обработчика
            try {
                System.out.println("Executing handler for " + request.getMethod() + " " + request.getPath());
                handler.handle(request, output);
                System.out.println("Request processed successfully");
            } catch (SocketTimeoutException e) {
                System.err.println("Handler timeout: " + e.getMessage());
                sendResponse(output, 408, "Request Timeout");
            } catch (Exception e) {
                System.err.println("Handler error: " + e.getMessage());
                e.printStackTrace(); // Добавляем полный стек ошибки
                sendResponse(output, 500, "Internal Server Error");
            }

        } catch (SocketTimeoutException e) {
            System.out.println("Timeout with client " + clientAddress + ": " + e.getMessage());
        } catch (IOException e) {
            System.err.println("IO error with client " + clientAddress + ": " + e.getMessage());
            e.printStackTrace(); // Добавляем вывод стека ошибки
        } catch (Exception e) {
            System.err.println("Unexpected error with client " + clientAddress + ": " + e.getMessage());
            e.printStackTrace(); // Добавляем вывод стека ошибки
        } finally {
            System.out.println("Connection closed with " + clientAddress);
        }
    }

    private Request parseRequest(InputStream inputStream, Socket socket) throws IOException {
        // Читаем строку запроса
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        String requestLine = reader.readLine();

        // Проверка на пустую строку запроса
        if (requestLine == null || requestLine.isEmpty()) {
            System.out.println("Empty request line");
            return null;
        }

        System.out.println("Raw request line: " + requestLine);

        String[] parts = requestLine.split(" ");
        if (parts.length != 3) {
            System.out.println("Invalid request format: " + requestLine);
            return null;
        }

        String method = parts[0].toUpperCase();
        String path = parts[1];
        Map<String, String> headers = new HashMap<>();

        // Читаем заголовки до пустой строки
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                headers.put(
                        line.substring(0, separator).trim().toLowerCase(), // Преобразуем к нижнему регистру для единообразия
                        line.substring(separator + 1).trim()
                );
            }
        }

        // Логируем заголовки для отладки
        System.out.println("Headers received: " + headers);

        // Обрабатываем тело запроса, если это POST, PUT или другой метод с телом
        InputStream body = null;

        if (("POST".equals(method) || "PUT".equals(method)) && headers.containsKey("content-length")) {
            try {
                int contentLength = Integer.parseInt(headers.get("content-length"));
                System.out.println("Content-Length: " + contentLength);

                if (contentLength > 0) {
                    // Устанавливаем таймаут для чтения тела
                    try {
                        if (socket != null) {
                            socket.setSoTimeout(BODY_READ_TIMEOUT);
                            System.out.println("Set body read timeout to " + BODY_READ_TIMEOUT + "ms");
                        }
                    } catch (Exception e) {
                        System.out.println("Could not set body read timeout: " + e.getMessage());
                    }

                    // Используем отдельное чтение сырых байтов вместо BufferedReader
                    body = extractExactBodyContent(inputStream, contentLength);
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid Content-Length header: " + e.getMessage());
            }
        }

        return new Request(method, path, headers, body);
    }

    // Улучшенный метод для точного чтения тела запроса заданной длины
    private InputStream extractExactBodyContent(InputStream input, int contentLength) throws IOException {
        System.out.println("Trying to read " + contentLength + " bytes from request body");

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096]; // Увеличен размер буфера для более эффективного чтения
        int bytesRead;
        int totalBytesRead = 0;
        long startTime = System.currentTimeMillis();
        long timeoutMillis = BODY_READ_TIMEOUT;

        // Читаем данные порциями с контролем времени
        while (totalBytesRead < contentLength) {
            // Проверяем таймаут
            if (System.currentTimeMillis() - startTime > timeoutMillis) {
                System.out.println("Body read timeout after " + totalBytesRead + " bytes");
                break;
            }

            // Определяем сколько еще нужно прочитать
            int remaining = contentLength - totalBytesRead;
            int maxToRead = Math.min(data.length, remaining);

            try {
                bytesRead = input.read(data, 0, maxToRead);
            } catch (SocketTimeoutException e) {
                System.out.println("Socket timeout while reading body after " + totalBytesRead + " bytes: " + e.getMessage());
                break;
            }

            if (bytesRead == -1) {
                System.out.println("End of stream reached after reading " + totalBytesRead + " bytes");
                break; // Достигнут конец потока
            }

            buffer.write(data, 0, bytesRead);
            totalBytesRead += bytesRead;
            System.out.println("Read " + bytesRead + " bytes, total: " + totalBytesRead + "/" + contentLength);
        }

        byte[] bodyContent = buffer.toByteArray();
        System.out.println("Body size: " + bodyContent.length + " bytes read");

        // Для отладки выводим часть содержимого (первые 100 байт)
        if (bodyContent.length > 0) {
            String preview = new String(bodyContent, 0, Math.min(bodyContent.length, 100), StandardCharsets.UTF_8);
            System.out.println("Body preview: " + preview + (bodyContent.length > 100 ? "..." : ""));
        }

        if (bodyContent.length < contentLength) {
            System.out.println("WARNING: Read only " + bodyContent.length + " bytes out of " + contentLength);
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