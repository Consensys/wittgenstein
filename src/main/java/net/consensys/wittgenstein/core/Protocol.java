package net.consensys.wittgenstein.core;

public interface Protocol {

  Protocol copy();
  //TDOO: Change Inconsistency between start methods some are void some pass an object some are named differently
  //void init();
  //Network network();
}
