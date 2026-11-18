BENCH_JAR := durastep-benchmarks/target/durastep-benchmarks.jar

.PHONY: build bench bench-throughput bench-latency

build:
	./mvnw -B -ntp -q install -Dmaven.test.skip=true

$(BENCH_JAR):
	./mvnw -B -ntp -q package -pl durastep-benchmarks -am -Dmaven.test.skip=true

bench: $(BENCH_JAR)
	java -jar $(BENCH_JAR) all $(ARGS)

bench-throughput: $(BENCH_JAR)
	java -jar $(BENCH_JAR) throughput $(ARGS)

bench-latency: $(BENCH_JAR)
	java -jar $(BENCH_JAR) latency $(ARGS)
