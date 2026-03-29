public class Distance {
    public static void main(String[] args) {

        if (args.length < 4) {
            System.out.println("请提供 4 个参数：x1 y1 x2 y2");
            return;
        }

        double x1 = Double.parseDouble(args[0]);
        double y1 = Double.parseDouble(args[1]);
        double x2 = Double.parseDouble(args[2]);
        double y2 = Double.parseDouble(args[3]);

        double distance = Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));

        System.out.println("两点之间的距离为：" + distance);
    }
}