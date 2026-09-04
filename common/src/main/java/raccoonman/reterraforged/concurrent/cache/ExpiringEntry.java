package raccoonman.reterraforged.concurrent.cache;

public interface ExpiringEntry {
    long getTimestamp();

	default boolean canEvict() {
		return true;
	}
    
    default void close() {
    }
}
