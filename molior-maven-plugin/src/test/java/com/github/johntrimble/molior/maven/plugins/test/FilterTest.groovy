package com.github.johntrimble.molior.maven.plugins.test

import org.junit.Test

import com.github.johntrimble.molior.maven.plugins.Filter

import static org.junit.Assert.assertTrue
import static org.junit.Assert.assertFalse

class FilterTest {

  private Filter f(String s) {
    return new Filter(s)
  }
  
  @Test
  public void testBasic() {
    // Try some basic operations on a simple object
    [a: 'b'].with {
      assertTrue f("(a=b)")(it)
      assertFalse f("(a=z)")(it)
      assertTrue f("(|(a=z)(a=b))")(it)
      assertFalse f("(&(a=z)(a=b))")(it)
      assertTrue f("(!(a=z))")(it)
      assertFalse f("(!(a=b))")(it)
    }
    
    // Try testing non-existant properties
    [a: 'b'].with {
      assertFalse f("(b=a)")(it)
      assertFalse f("(&(b=a)(a=b))")(it)
      assertTrue f("(|(b=a)(a=b))")(it)
    }
  }
}
