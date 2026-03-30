public class PermutationInverse {
    public static void main(String[] args) {
        // 示例：σ = (2, 5, 4, 3, 1)
        int[] sigma = {2, 5, 4, 3, 1};
        int N = sigma.length;
        
        System.out.print("原置换: ");
        printPermutation(sigma);
        
        // 求逆（原地修改）
        inverseInPlace(sigma);
        
        System.out.print("逆置换: ");
        printPermutation(sigma);
    }
    
    /**
     * 原地求置换的逆
     * 算法：遍历每个位置，将 sigma[i] 和 sigma[sigma[i]-1] 交换
     * 直到形成逆置换
     */
    public static void inverseInPlace(int[] sigma) {
        int N = sigma.length;
        
        // 转换为 0-based
        for (int i = 0; i < N; i++) {
            sigma[i]--;
        }
        
        for (int i = 0; i < N; i++) {
            // 如果当前位置 i 的值不是 i，则交换
            while (sigma[i] != i) {
                int j = sigma[i];
                // 交换 sigma[i] 和 sigma[j]
                int temp = sigma[i];
                sigma[i] = sigma[j];
                sigma[j] = temp;
            }
        }
        
        // 转换回 1-based
        for (int i = 0; i < N; i++) {
            sigma[i]++;
        }
    }
    
    public static void printPermutation(int[] perm) {
        System.out.print("(");
        for (int i = 0; i < perm.length; i++) {
            System.out.print(perm[i]);
            if (i < perm.length - 1) System.out.print(", ");
        }
        System.out.println(")");
    }
}