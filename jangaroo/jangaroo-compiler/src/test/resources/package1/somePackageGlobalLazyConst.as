package package1 {

import package1.someOtherPackage.SomeOtherClass;

// This comment to vanish in API
[Lazy]
/**
 * Some package-global documentation;
 */
public const somePackageGlobalLazyConst:SomeOtherClass
  = new SomeOtherClass();

}