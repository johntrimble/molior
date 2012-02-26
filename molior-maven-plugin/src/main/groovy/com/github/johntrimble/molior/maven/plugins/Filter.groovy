/**
 * Copyright (C) 2012 John Trimble <trimblej@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.johntrimble.molior.maven.plugins

class Filter {
  def ast
  def matcher
  
  Filter(String s) {
    ast = parse(s)
    matcher = createMatcher(ast)
  }
  
  boolean isCase(def x) {
    return this.matches(x)
  }
  
  boolean matches(def x) {
    return this.matcher(x)
  }
  
  boolean call(def x) {
    return this.matches(x)
  }
  
  public def createMatcher(def expression) {
    expression = expression.clone()
    switch(expression.remove(0)) {
      case '&':
        return (expression.collect { createMatcher it }
          .with { List matchers -> { def v -> matchers.every { it(v) } } })
        break
      case '|':
        return (expression.collect { createMatcher it }
          .with { List matchers -> { def v -> matchers.any { it(v) } } })
        break
      case '!':
        return (expression.collect { createMatcher it}
          .with { List matchers -> { def v -> !matchers.any { it(v) } } })
        break
      case '=':
        return {def v -> v."${expression[0]}" == expression[1]}
        break
    }
  }
  
  public String toString() {
    return this.ast.toString()
  }
  
  public def parse(String text) {
    def pos = 0
    def parseExpression
    parseExpression = { def s ->
      def r = []
      pos++
      switch(s[pos]) {
        case ['&', '|'] :
          r << s[pos]
          pos++
          while( s[pos] != ')' ) {
            r << parseExpression(s)
          }
          pos++
          break
        case '!':
          r << s[pos]
          pos++
          r << parseExpression(s)
          pos++
          break
        default:
          def i = s.indexOf(')', pos)
          r << '='
          s[pos..(i-1)].split('=').collect r, {it}
          pos = i+1
      }
      return r
    }
    return parseExpression(text)
  }  
}
