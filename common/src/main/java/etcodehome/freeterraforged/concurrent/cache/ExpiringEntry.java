package etcodehome.freeterraforged.concurrent.cache;

public interface ExpiringEntry {
    long getTimestamp();

	default boolean canEvict() {
		return true;
	}
    
    default void close() {
    }
}
