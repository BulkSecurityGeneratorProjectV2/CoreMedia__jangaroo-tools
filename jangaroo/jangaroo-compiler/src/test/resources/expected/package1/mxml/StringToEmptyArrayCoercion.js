/*package package1.mxml{
import ext.*;
import net.jangaroo.ext.Exml;*/
Ext.define("package1.mxml.StringToEmptyArrayCoercion", function(StringToEmptyArrayCoercion) {/*public class StringToEmptyArrayCoercion extends Panel{public*/function StringToEmptyArrayCoercion$(config/*:StringToEmptyArrayCoercion=null*/){if(arguments.length<=0)config=null;
    this.super$4x7N(net.jangaroo.ext.Exml.apply(AS3.cast(StringToEmptyArrayCoercion,{
  items:[]
}),config));
}/*}}

============================================== Jangaroo part ==============================================*/
    return {
      extend: "Ext.Panel",
      constructor: StringToEmptyArrayCoercion$,
      super$4x7N: function() {
        Ext.Panel.prototype.constructor.apply(this, arguments);
      },
      requires: ["Ext.Panel"],
      uses: ["net.jangaroo.ext.Exml"]
    };
});
