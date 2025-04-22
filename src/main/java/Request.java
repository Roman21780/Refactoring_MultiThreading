import java.io.InputStream;
import java.util.Map;

class Request {
    private final String method;
    private final String path;
    private final Map<String, String> headers;
    private final InputStream body;
    private final Map<String, String> queryParams;

    public Request(String method, String path,
                   Map<String, String> headers,
                   InputStream body,
                   Map<String, String> queryParams) {
        this.method = method;
        this.path = path;
        this.headers = headers;
        this.body = body;
        this.queryParams = queryParams;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getHeader(String name) {
        return headers.get(name);
    }

    public InputStream getBody() {
        return body;
    }

    public String getQueryParam(String name) {
        return queryParams.get(name);
    }
}
