import java.io.*;
import java.nio.file.*;

public class Cat {
    public static void main(String[] args) {
        if (args.length == 0) return;
        for (String filename : args) {
            Path path = Paths.get(filename);
            try (InputStream in = Files.newInputStream(path)) {
                Files.copy(in, System.out);
            } catch (IOException e) {
                System.err.println("Error reading file " + filename + ": " + e.getMessage());
            }
        }
        System.out.flush();
    }
}