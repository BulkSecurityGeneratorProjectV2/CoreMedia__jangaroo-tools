/*package package1.mxml{
import ext.*;
import net.jangaroo.ext.Exml;*/
Ext.define("package1.mxml.StringToArrayCoercion", function(StringToArrayCoercion) {/*public class StringToArrayCoercion extends Panel{public*/function StringToArrayCoercion$(config/*:StringToArrayCoercion=null*/){if(arguments.length<=0)config=null;
    this.super$Sy5g(net.jangaroo.ext.Exml.apply(AS3.cast(StringToArrayCoercion,{
           items: ["just a joke"]
}),config));
}/*}}

============================================== Jangaroo part ==============================================*/
    return {
      extend: "Ext.Panel",
      constructor: StringToArrayCoercion$,
      super$Sy5g: function() {
        Ext.Panel.prototype.constructor.apply(this, arguments);
      },
      requires: ["Ext.Panel"],
      uses: ["net.jangaroo.ext.Exml"]
    };
});
