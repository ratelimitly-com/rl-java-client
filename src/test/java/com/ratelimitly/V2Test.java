package com.ratelimitly;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class V2Test {
 @Test void sharedKats() throws Exception { V2SharedMain.main(new String[0]); }
 @Test void annualCap() throws Exception {
  var info = ApiKeyDecoder.decode("rl-cookie1qgqsqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqz8scfwu4f");
  assertEquals(2, info.formatVersion());
  assertEquals(31536000000L, info.quotas().orElseThrow().rateWindowSizeMsMax());
 }
}
