package etcodehome.freeterraforged.world.worldgen.util;

public class Seed {
    private final long root;
    private long value;
    
    public Seed(long value) {
        this.root = value;
        this.value = value;
    }
    
    public int next() {
        return toInt(this.value++);
    }
    
    public long get() {
        return this.value;
    }
    
    public long root() {
        return this.root;
    }
    
    public Seed split() {
        return new Seed(this.root);
    }
    
    public Seed offset(int offset) {
        return new Seed(this.root + offset);
    }

    public static int toInt(long seed) {
        if (seed == (int) seed) {
            return (int) seed;
        }

        long mixed = seed;
        mixed = (mixed ^ (mixed >>> 33)) * -49064778989728563L;
        mixed = (mixed ^ (mixed >>> 33)) * -4265267296055464877L;
        mixed ^= mixed >>> 33;
        return (int) (mixed ^ (mixed >>> 32));
    }
}
