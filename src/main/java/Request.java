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
    private final Map<String, List<String>> queryParams;
    private final List<NameValuePair> queryParamsList;

    // Для POST параметров
    private final Map<String, List<String>> postParams;
    private final List<NameValuePair> postParamsList;

    // Для multipart данных
    private final List<Part> parts;

    public Request(String method, String path, Map<String, String> headers, InputStream body) throws IOException {
        this.method = method;
        this.rawPath = path;
        this.path = path.split("\\?", 2)[0];
        this.headers = Collections.unmodifiableMap(headers);
        this.bodyBytes = body != null ? body.readAllBytes() : new byte[0];

        // парсим query параметры в конструкторе
        this.queryParamsList = parseQueryParams();
        this.queryParams = convertToMap(queryParamsList);

        // парсим POST параметры
        Pair<Map<String, List<String>>, List<NameValuePair>> parsedPost = parsePostParams();
        this.postParams = parsedPost.getLeft();
        this.postParamsList = parsedPost.getRight();

        // Парсим multipart данные
        if (isMultipart()) {
            String contentType = headers.get("content-type");
            String boundary = extractBoundary(contentType);
            if (boundary != null) {
                this.parts = parseMultipartData(boundary);
            } else {
                System.err.println("Could not extract boundary from Content-Type");
                this.parts = Collections.emptyList();
            }
        } else {
            this.parts = Collections.emptyList();
        }

    }

    // ========== Query параметры ==========
    private List<NameValuePair> parseQueryParams() {
        if (!rawPath.contains("?")) {
            return Collections.emptyList();
        }
        try {
            String query = rawPath.substring(rawPath.indexOf('?') + 1);
            return URLEncodedUtils.parse(query, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("Error parsing query parameters: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private Map<String, List<String>> convertToMap(List<NameValuePair> params) {
        Map<String, List<String>> map = new HashMap<>();
        for (NameValuePair pair : params) {
            map.computeIfAbsent(pair.getName(), k -> new ArrayList<>()).add(pair.getValue());
        }
        map.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return Collections.unmodifiableMap(map);
    }

    public Map<String, List<String>> getQueryParams() {
        return queryParams;
    }

    public List<NameValuePair> getQueryParamsList() {
        return queryParamsList;
    }

    public String getQueryParam(String name) {
        List<String> values = queryParams.get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    public List<String> getQueryParamValues(String name) {
        return queryParams.getOrDefault(name, Collections.emptyList());
    }

    public boolean hasQueryParam(String name) {
        return queryParams.containsKey(name);
    }

    // ========== POST параметры ==========
    private Pair<Map<String, List<String>>, List<NameValuePair>> parsePostParams() {
        if (!("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) || !isFormUrlEncoded()) {
            return new Pair<>(Collections.emptyMap(), Collections.emptyList());
        }
        try {
            String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
            List<NameValuePair> parsed = URLEncodedUtils.parse(bodyStr, StandardCharsets.UTF_8);
            Map<String, List<String>> map = convertToMap(parsed);
            return new Pair<>(map, parsed);
        } catch (Exception e) {
            System.err.println("Error parsing POST parameters: " + e.getMessage());
            return new Pair<>(Collections.emptyMap(), Collections.emptyList());
        }
    }

    public Map<String, List<String>> getPostParams() {
        return postParams;
    }

    public List<NameValuePair> getPostParamsList() {
        return postParamsList;
    }

    public String getPostParam(String name) {
        List<String> values = postParams.get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    public List<String> getPostParamValues(String name) {
        return postParams.getOrDefault(name, Collections.emptyList());
    }

    public boolean hasPostParam(String name) {
        return postParams.containsKey(name);
    }

    // ========== Multipart данные ==========
    private List<Part> parseMultipartData(String boundary) {
        List<Part> resultParts = new ArrayList<>();
        try {
            byte[] fullBoundary = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
            byte[] endBoundary = ("--" + boundary + "--").getBytes(StandardCharsets.UTF_8);

            List<Integer> boundaryPositions = findBoundaryPositions(bodyBytes, fullBoundary);
            int endPosition = findSequence(bodyBytes, endBoundary, 0, bodyBytes.length);

            if (boundaryPositions.isEmpty()) {
                System.out.println("Границы не найдены");
                return resultParts;
            }

            System.out.println("Найдено границ: " + boundaryPositions.size());

            for (int i = 0; i < boundaryPositions.size(); i++) {
                int start = boundaryPositions.get(i) + fullBoundary.length;
                int end = (i < boundaryPositions.size() - 1) ? boundaryPositions.get(i + 1) : endPosition;

                if (end == -1) continue;

                // Пропускаем пустые части
                if (end - start <= 2) continue;

                byte[] headerEnd = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
                int headerEndPos = findSequence(bodyBytes, headerEnd, start, end);

                if (headerEndPos == -1) {
                    System.out.println("Не найден конец заголовков для части " + i);
                    continue;
                }

                // Чтение заголовков
                byte[] headersBytes = Arrays.copyOfRange(bodyBytes, start, headerEndPos);
                String headersText = new String(headersBytes, StandardCharsets.UTF_8);
                Map<String, String> headers = parseHeaders(headersText);

                String contentDisposition = headers.get("content-disposition");
                if (contentDisposition == null) {
                    System.out.println("Отсутствует Content-Disposition в части " + i);
                    continue;
                }

                String fieldName = extractFieldName(contentDisposition);
                String fileName = extractFileName(contentDisposition);
                boolean isFile = fileName != null && !fileName.isEmpty();
                String contentType = headers.get("content-type");

                // Чтение содержимого
                int contentStart = headerEndPos + headerEnd.length;
                int contentEnd = end - 2; // Убираем \r\n в конце
                byte[] content = Arrays.copyOfRange(bodyBytes, contentStart, contentEnd);

                if (!isFile) {
                    String textContent = new String(content, StandardCharsets.UTF_8).trim();
                    content = textContent.getBytes(StandardCharsets.UTF_8);
                }

                System.out.println("Обработана часть: " + fieldName +
                        (isFile ? " (файл: " + fileName + ")" : " (текст)"));

                resultParts.add(new Part(fieldName, fileName, contentType, content, isFile));
            }
        } catch (Exception e) {
            System.err.println("Ошибка разбора multipart: " + e.getMessage());
            e.printStackTrace();
        }
        return resultParts;
    }

    private List<Part> parseMultipartParts(String boundary) {
        List<Part> resultParts = new ArrayList<>();
        try {
            List<Integer> boundaryPositions = findBoundaryPositions(bodyBytes, boundary.getBytes(StandardCharsets.UTF_8));
            if (boundaryPositions.size() < 2) {
                return resultParts;
            }
            for (int i = 0; i < boundaryPositions.size() - 1; i++) {
                int start = boundaryPositions.get(i);
                int end = boundaryPositions.get(i + 1);
                if (i == 0) continue; // Пропускаем первую границу

                byte[] headerEnd = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
                int headerEndPos = findSequence(bodyBytes, headerEnd, start, end);
                if (headerEndPos == -1) continue;

                byte[] headersBytes = Arrays.copyOfRange(bodyBytes, start, headerEndPos);
                String headersText = new String(headersBytes, StandardCharsets.UTF_8);
                Map<String, String> partHeaders = parseHeaders(headersText);

                String contentDisposition = partHeaders.get("content-disposition");
                if (contentDisposition == null) continue;

                String fieldName = extractFieldName(contentDisposition);
                String fileName = extractFileName(contentDisposition);
                boolean isFile = (fileName != null && !fileName.isEmpty());
                String contentType = partHeaders.get("content-type");

                int contentStart = headerEndPos + 4;
                int contentEnd = end - 2;
                byte[] content = Arrays.copyOfRange(bodyBytes, contentStart, contentEnd);
                resultParts.add(new Part(fieldName, fileName, contentType, content, isFile));
            }
        } catch (Exception e) {
            System.err.println("Error parsing multipart parts: " + e.getMessage());
        }
        return resultParts;
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
        return name.trim().isEmpty() ? null : name.trim();
    }

    private Map<String, String> parseHeaders(String headersText) {
        Map<String, String> headers = new HashMap<>();
        String[] lines = headersText.split("\r\n");
        for (String line : lines) {
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String name = line.substring(0, colonIndex).trim().toLowerCase();
                String value = line.substring(colonIndex + 1).trim();
                headers.put(name, value);
            }
        }
        return headers;
    }

    public Part getPart(String name) {
        return parts.stream()
                .filter(part -> part.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public List<Part> getParts() {
        return parts;
    }

    public List<Part> getParts(String name) {
        return parts.stream()
                .filter(part -> part.getName().equals(name))
                .collect(Collectors.toList());
    }

    // ========== Вспомогательные методы ==========
    boolean isMultipart() {
        String contentType = headers.get("content-type");
        return contentType != null && contentType.startsWith("multipart/form-data");
    }

    boolean isFormUrlEncoded() {
        String contentType = headers.get("content-type");
        return contentType != null && contentType.contains("x-www-form-urlencoded");
    }

    private String extractBoundary(String contentType) {
        int boundaryIndex = contentType.indexOf("boundary=");
        if (boundaryIndex == -1) return null;
        String boundary = contentType.substring(boundaryIndex + "boundary=".length());
        if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }
        return boundary.trim();
    }

    private List<Integer> findBoundaryPositions(byte[] data, byte[] boundary) {
        List<Integer> positions = new ArrayList<>();
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
                i += boundary.length - 1;
            }
        }
        return positions;
    }

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
        return fileName.trim().isEmpty() ? null : fileName.trim();
    }

    // ========== Базовые геттеры ==========
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getRawPath() { return rawPath; }
    public Map<String, String> getHeaders() { return headers; }
    public InputStream getBody() { return new ByteArrayInputStream(bodyBytes); }
    public String getBodyAsString() { return new String(bodyBytes, StandardCharsets.UTF_8); }

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
        public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        public String getString() { return new String(content, StandardCharsets.UTF_8); }
        public byte[] getBytes() { return content.clone(); }
    }

    // Вспомогательный класс для хранения пар значений
    private static class Pair<L, R> {
        private final L left;
        private final R right;

        public Pair(L left, R right) {
            this.left = left;
            this.right = right;
        }

        public L getLeft() { return left; }
        public R getRight() { return right; }
    }
}