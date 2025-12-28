package examples;

class B {
    void bar() {}
}

public class PointerTest {
    public static void main(String[] args) {
        B x = new B();
        B y = x;

        y.bar();   // safe
    }
}
