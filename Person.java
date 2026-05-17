public abstract class Person {
    private Name name;
    private int age;
    private Gender gender;

    public Person() {
        this.name = new Name();
        this.age = 0;
        this.gender = null;
    }

    public Person(int age, Gender gender, Name name) {
        this.age = age;
        this.gender = gender;
        this.name = name;
    }

    public Name getName() { return name; }
    public void setName(Name name) { this.name = name; }
    public int getAge() { return age; }
    public void setAge(int age) { this.age = age; }
    public Gender getGender() { return gender; }
    public void setGender(Gender gender) { this.gender = gender; }

    // 普通方法
    public void talk() {
        System.out.println("Hi, how is it going?");
    }

    public void talk(String topic) {
        System.out.println("Let's talk about " + topic + ".");
    }

    public void chatWith(Person p, String topic) {
        String aName = this.name.toString();
        String bName = p.name.toString();
        System.out.println(aName + " to " + bName + ": Let's talk about " + topic + ".");
    }

    @Override
    public String toString() {
        return name.toString() + ", " + age + ", " + gender;
    }

    // 新增抽象方法
    public abstract void work();
}