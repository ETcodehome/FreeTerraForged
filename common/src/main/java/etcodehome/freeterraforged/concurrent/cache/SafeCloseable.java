package etcodehome.freeterraforged.concurrent.cache;

public interface SafeCloseable extends AutoCloseable {
    void close();
}
