import cc.jfire.dson.Dson;
import java.util.Map;

public class TestDson {
    public static void main(String[] args) {
        String json = "{\"data\":\"\\r\"}";
        System.out.println("JSON: " + json);
        
        Map map = Dson.fromString(Map.class, json);
        String data = (String) map.get("data");
        
        System.out.println("data length: " + data.length());
        System.out.print("data bytes: ");
        for (byte b : data.getBytes()) {
            System.out.print(String.format("%02X ", b));
        }
        System.out.println();
        
        // 正确的回车符应该是 0x0D，长度为1
        // 如果是字面量 \r，则是 0x5C 0x72，长度为2
    }
}
