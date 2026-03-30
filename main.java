import java.io.*;

public class FileIO {
    public static void main(String[] args) {
        String filename = "test.txt";
        
        // 1. 逐行写入四行内容
        String[] lines = {
            "《老人与海》这本小说是根据真人真事写的。第一次世界大战结束后，海明威移居古巴，认识了老渔民格雷戈里奥·富恩特斯。",
            "1930年，海明威乘的船在暴风雨中沉没，富恩特斯搭救了海明威。从此，海明威与富恩特斯结下了深厚的友谊，并经常一起出海捕鱼。",
            "The novel The Old Man and the Sea is based on a real story. After the end of World War I, Hemingway moved to Cuba, where he met an old fisherman, Gregorio Fuentes.",
            "In 1930, Hemingway was rescued by Fuentes when his boat sank in a storm. From then on, Hemingway and Fuentes formed a deep friendship, and often went fishing together."
        };
        
        File file = new File(filename);
        if (file.exists()) {
            file.delete();
        }
        
        // 调用四次 writeStringToFile，每次写一行
        for (String line : lines) {
            FileIO.writeStringToFile(line + "\n", filename);
        }
        
        // 2. 获取第5个字符
        char ch5 = FileIO.getCharFromFile(filename, 5);
        System.out.println("第5个字符: " + ch5);
        
        // 3. 获取第3行
        String line3 = FileIO.getLineFromFile(filename, 3);
        System.out.println("第3行: " + line3);
        
        // 4. 获取所有行
        String[] allLines = FileIO.getAllLinesFromFile(filename);
        System.out.println("所有行:");
        for (int i = 0; i < allLines.length; i++) {
            System.out.println((i+1) + ": " + allLines[i]);
        }
    }
}