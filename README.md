# xnetp-poc

R&D, Java network packets processing with JNI, subprojects:
* Java api for group send / receive
* Linux AF_PACKET V3 (rx ring) packets' receive (via ByteBuffer \*hack\*)
* threads' affinity via jni for rx threads
* Linux netfilter kernel module for packet filtering
* Linux procfs realtime monitoring
* Custom configuration of netdata dashboards
