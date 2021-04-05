package package1 {

/**
 * This is an example of an API-only class ("native API stub").
 */
[Native("SomeNativeClass")]
public class SomeNativeClass extends SomeNativeSuperClass {

  /**
   * Some constructor doc.
   */
  public function SomeNativeClass() {
  }

  // This comment must disappear!
  /**
   * Some static method doc.
   */
  public static native function foo();

  /**
   * Some method doc.
   */
  public native function bar();

  /**
   * Some renamed method.
   */
  [Native("delete")]
  public native function delete_(): void;
}
}