package examples; 
class A {
    void foo() { }
}

public class PointerTest {

    public static void main(String[] args) {

        A a = new A();
        A b = a;
        b = null;

        a.foo();   // safe
        b.foo();   // potential null dereference
    }
}
