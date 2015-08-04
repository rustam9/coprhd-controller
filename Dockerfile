FROM rustam9/coprhd-runtime:latest
 
RUN mkdir /coprhd-controller
ADD . /coprhd-controller/
RUN zypper --non-interactive install git make gcc-c++ tar java-1_7_0-openjdk-devel ca-certificates-mozilla rpm-build && \
    cd coprhd-controller && JAVA_HOME=/usr/lib64/jvm/java-1.7.0-openjdk make BUILD_TYPE=oss rpm && \
    DO_NOT_START="yes" rpm -iv build/RPMS/x86_64/storageos-*.x86_64.rpm && \
    cd .. && rm -rf /coprhd-controller && \
    zypper --non-interactive remove git make gcc-c++ tar java-1_7_0-openjdk-devel ca-certificates-mozilla rpm-build && zypper clean
RUN ln -s /coprhd/ovfenv.properties /etc
  
CMD ["/sbin/init"]
