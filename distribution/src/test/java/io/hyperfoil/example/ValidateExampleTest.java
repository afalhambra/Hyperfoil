package io.hyperfoil.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import io.hyperfoil.api.config.Benchmark;
import io.hyperfoil.api.config.BenchmarkBuilder;
import io.hyperfoil.api.config.HttpBuilder;
import io.hyperfoil.api.config.Phase;
import io.hyperfoil.api.config.PhaseBuilder;
import io.hyperfoil.api.config.PhaseForkBuilder;
import io.hyperfoil.api.config.Scenario;
import io.hyperfoil.core.impl.LocalBenchmarkData;
import io.hyperfoil.core.parser.BenchmarkParser;
import io.hyperfoil.function.SerializableSupplier;
import io.hyperfoil.util.Util;

@RunWith(Parameterized.class)
public class ValidateExampleTest {
   private final String exampleFile;

   public ValidateExampleTest(String exampleFile) {
      this.exampleFile = exampleFile;
   }

   @Parameterized.Parameters
   public static Collection<Object[]> listExamples() throws IOException {
      return Files.list(Paths.get("examples"))
            .filter(p -> p.toString().endsWith(".hf.yaml"))
            .filter(p -> p.toFile().isFile())
            .map(p -> new Object[]{ p.toString() })
            .collect(Collectors.toList());
   }

   private InputStream loadOrFail() {
      InputStream stream = getClass().getClassLoader().getResourceAsStream(exampleFile);
      if (stream == null) {
         fail("Cannot load file " + exampleFile);
      }
      return stream;
   }

   @Test
   public void testSerializable() {
      try {
         Benchmark benchmark = BenchmarkParser.instance().buildBenchmark(loadOrFail(), new LocalBenchmarkData());
         assertThat(benchmark.name()).isEqualTo(exampleFile.replace(".hf.yaml", "").replaceFirst("[^" + File.separatorChar + "]*.", ""));
         byte[] bytes = Util.serialize(benchmark);
         assertThat(bytes).isNotNull();
      } catch (Exception e) {
         throw new AssertionError("Failure in " + exampleFile, e);
      }
   }

   @Test
   public void testCopy() {
      try {
         String source = io.hyperfoil.core.util.Util.toString(loadOrFail());
         BenchmarkBuilder original = BenchmarkParser.instance().builder(source, new LocalBenchmarkData());
         BenchmarkBuilder builder = new BenchmarkBuilder(null, new LocalBenchmarkData());
         BenchmarkBuilder.httpForTesting(original).forEach(http -> {
            HttpBuilder newHttp = builder.decoupledHttp();
            newHttp.readFrom(http);
            builder.addHttp(newHttp);
         });
         builder.prepareBuild();
         for (PhaseBuilder<?> phase : BenchmarkBuilder.phasesForTesting(original)) {
            TestingPhaseBuilder copy = new TestingPhaseBuilder(builder);
            // This triggers the copy
            copy.readForksFrom(phase);
            copy.prepareBuild();
            copy.build(null, new AtomicInteger());
         }
      } catch (Exception e) {
         throw new AssertionError("Failure in " + exampleFile, e);
      }
   }

   private static class TestingPhaseBuilder extends PhaseBuilder<TestingPhaseBuilder> {
      protected TestingPhaseBuilder(BenchmarkBuilder builder) {
         super(builder, "-for-testing-" + ThreadLocalRandom.current().nextLong());
      }

      @Override
      protected Phase buildPhase(SerializableSupplier<Benchmark> benchmark, SerializableSupplier<Phase> ps, int phaseId, int iteration, PhaseForkBuilder f) {
         Scenario scenario = f.scenario().build(ps);
         return new Phase.Noop(null, 0, 0, name, null, null, null, scenario);
      }
   }
}
