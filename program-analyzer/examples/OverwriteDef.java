package examples;

public class OverwriteDef {
    public static void main(String[] args) {
        int x = 10;   // D1
        x = 20;       // D2 (kills D1)
        x = 30;       // D3 (kills D2)

        System.out.println(x);
    }
}
