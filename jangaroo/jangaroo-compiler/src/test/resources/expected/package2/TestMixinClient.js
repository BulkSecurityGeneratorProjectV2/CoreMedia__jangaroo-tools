/*package package2 {*/

Ext.define("package2.TestMixinClient", function(TestMixinClient) {/*public class TestMixinClient implements ITestMixin {

  public*/ function TestMixinClient$(thing/*:String*/) {
    this.mix(thing);
  }/*

  /** @inheritDoc * /
  [Bindable]
  public native function get foo():Number;

  /** @private * /
  [Bindable]
  public native function set foo(value:Number):void;

  /** @inheritDoc * /
  public native function mix(thing:String):String;
}
}

============================================== Jangaroo part ==============================================*/
    return {
      mixins: ["package2.TestMixin"],
      constructor: TestMixinClient$,
      requires: ["package2.TestMixin"]
    };
});
