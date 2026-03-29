public class PrimeGenerator {
    public static void main(String[] args) {
        int limit = 100;  // 默认值
        
        if (args.length > 0) {
            limit = Integer.parseInt(args[0]);
        }
        
        System.out.println("1到" + limit + "之间的素数：");
        
        for (int i = 2; i <= limit; i++) {
            if (isPrime(i)) {
                System.out.print(i + " ");
            }
        }
        System.out.println();  // 换行
    }
    
    // 判断素数的函数（和 C 语言完全一样）
    public static boolean isPrime(int n) {
        if (n <= 1) return false;
        if (n == 2) return true;
        if (n % 2 == 0) return false;
        
        for (int i = 3; i * i <= n; i += 2) {
            if (n % i == 0) {
                return false;
            }
        }
        return true;
    }
}