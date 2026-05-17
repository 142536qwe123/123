public class TestAbstractPerson {
    public static void main(String[] args) {
        // 1. 尝试直接实例化抽象类 Person —— 编译器报错
         //Person p = new Person();   // 编译错误: Person is abstract; cannot be instantiated

        // 2. 正确做法：通过子类实例化
        Name name1 = new Name("Alice", "Johnson");
        Name name2 = new Name("Bob", "Smith");

        Person stu = new Student(20, Gender.FEMALE, name1, "Computer Science");
        Person tea = new Teacher(35, Gender.MALE, name2, "Mathematics");

        // 3. 调用 work() 方法（动态绑定）
        stu.work();   // 输出: I am studying Computer Science.
        tea.work();   // 输出: I am teaching Mathematics.

        // 4. 也可以调用 talk() 等普通方法
        stu.talk();   // Student 的 talk
        tea.talk();   // Teacher 的 talk
    }
}