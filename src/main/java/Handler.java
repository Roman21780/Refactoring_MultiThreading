import java.io.IOException;
import java.io.OutputStream;

@FunctionalInterface
public interface Handler {
    void handle(Request request, OutputStream responseStream) throws IOException;
}