package protocol;

import com.fasterxml.jackson.databind.ObjectMapper;

public class AppProtocol {

  public static byte[] encode(AppMessage message) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    byte[] json_header = mapper.writeValueAsBytes(message.getHeader());
  }
}
