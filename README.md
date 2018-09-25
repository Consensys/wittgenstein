# wittgenstein
Simulator for some PoS or consensus algorithms. Includes dfinity, casper IMD


## Why this name?
Wittgenstein was a concert pianist. He commissioned Ravel's Piano Concerto for the Left Hand, but changed it:
 'lines taken from the orchestral part and added to the solo, harmonies changed, parts added, bars cut and
  at the end a newly created series of great swirling arpeggios in the final cadenza.'

As it's often what happens with mock protocol implementations, it looked like the right name.


## How to build it
To check everything is correct:

mvn test

You ca build a jar with this maven command:

mvn package

## How to run it
Once built:

java -Xms6000m -Xmx12048m -classpath target/wittgenstein-1.0-SNAPSHOT.jar net.consensys.wittgenstein.protocol.OptimisticP2PSignature

But you're actually supposed to write code to implement your specific scenarios today. An obvious improvement
 would be to be able to define scenarios reusable between protocols.
