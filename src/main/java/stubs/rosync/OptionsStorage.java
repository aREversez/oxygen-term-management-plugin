package rosync;

import java.util.HashMap;
import java.util.Map;

/**
 * Stub for Oxygen OptionsStorage.
 * Used for compilation only.
 */
public class OptionsStorage {
    private static OptionsStorage instance = new OptionsStorage();
    private Map<String, String> store = new HashMap<>();

    public static OptionsStorage getInstance() { return instance; }
    public String get(String key) { return store.get(key); }
    public void put(String key, String value) { store.put(key, value); }
    public void save() {}
}
