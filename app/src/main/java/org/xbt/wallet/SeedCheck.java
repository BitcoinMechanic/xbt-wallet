package org.xbt.wallet;
import java.util.List;
final class SeedCheck {
    static boolean matches(String expected, List<String> entered) {
        if (expected == null || expected.trim().isEmpty()) return false;
        String[] words = expected.trim().split("\\s+");
        if (words.length != entered.size()) return false;
        for (int i = 0; i < words.length; i++)
            if (!words[i].equalsIgnoreCase(entered.get(i).trim())) return false;
        return true;
    }
}
