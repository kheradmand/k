// Copyright (c) 2014-2016 K Team. All Rights Reserved.
module SUM-RECURSIVE-SPEC
  imports KERNELC

rule
  <fun>... FUN:Map ...</fun>
  <k>
    sum_recursive(tv(int, N:Int))
  =>
    tv(int, (N +Int 1) *Int N /Int 2)
  ...</k>
requires N >=Int 0
endmodule
