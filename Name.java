public class Name {
    private String firstName;
    private String lastName;

    // 无参构造函数，默认设为 "Unknown"
    public Name() {
        this.firstName = "Unknown";
        this.lastName = "Unknown";
    }

    // 构造函数，初始化名和姓
    public Name(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }

    // 返回名
    public String getFirstName() {
        return firstName;
    }

    // 设置名
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    // 返回姓
    public String getLastName() {
        return lastName;
    }

    // 设置姓
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    // 转化为 String，返回 "First Last" 格式字符串
    @Override
    public String toString() {
        return firstName + " " + lastName;
    }

    // 比较两个 Name 对象内容是否一致（姓和名都相同即相同）
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Name other = (Name) obj;
        return firstName.equals(other.firstName) && lastName.equals(other.lastName);
    }
}
