import io.kotlintest.specs.StringSpec
import io.kotlintest.*

import edu.illinois.cs.cs125.janini.*

class TestSnippet : StringSpec({
  "should parse snippets" {
      Snippet("""{
class Test {
  int me = 0;
}
int testing() {
  int j = 0;
  return 10;
}
int i = 0;
i++;
}""")
  }
})
