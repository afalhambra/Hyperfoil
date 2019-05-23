package io.hyperfoil.cli.commands;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;

import io.hyperfoil.cli.Table;
import io.hyperfoil.cli.context.HyperfoilCommandInvocation;
import io.hyperfoil.client.Client;
import io.hyperfoil.client.RestClientException;
import io.hyperfoil.core.util.Util;

@CommandDefinition(name = "sessions", description = "Show sessions statistics")
public class Sessions extends BaseRunIdCommand {
   Table<Map.Entry<String, Client.MinMax>> SESSION_STATS = new Table<Map.Entry<String, Client.MinMax>>()
         .column("AGENT", Map.Entry::getKey)
         .column("MIN", e -> String.valueOf(e.getValue().min), Table.Align.RIGHT)
         .column("MAX", e -> String.valueOf(e.getValue().max), Table.Align.RIGHT);

   @Override
   public CommandResult execute(HyperfoilCommandInvocation invocation) throws CommandException {
      Client.RunRef runRef = getRunRef(invocation);
      Map<String, Map<String, Client.MinMax>> sessionStats = null;
      for (;;) {
         try {
            if (sessionStats != null) {
               clearLines(invocation, sessionStats.values().stream().mapToInt(Map::size).sum() + 2);
            }
            sessionStats = runRef.sessionStatsRecent();
            if (sessionStats == null || sessionStats.isEmpty()) {
               Client.Run run = runRef.get();
               if (run.terminated != null) {
                  invocation.println("Run " + run.id + " has terminated.");
                  invocation.print(SESSION_STATS.print("PHASE", toMapOfStreams(runRef.sessionStatsTotal())));
                  return CommandResult.SUCCESS;
               }
            }
            invocation.print(SESSION_STATS.print("PHASE", toMapOfStreams(sessionStats)));
            invocation.println("Press Ctrl+C to stop watching...");
            Thread.sleep(1000);
         } catch (RestClientException e) {
            invocation.println("ERROR: " + Util.explainCauses(e));
            throw new CommandException("Cannot display session stats.", e);
         } catch (InterruptedException e) {
            clearLines(invocation, 1);
            invocation.println("");
            return CommandResult.SUCCESS;
         }
      }
   }

   private Map<String, Stream<Map.Entry<String, Client.MinMax>>> toMapOfStreams(Map<String, Map<String, Client.MinMax>> sessionStats) {
      return sessionStats.entrySet().stream()
                     .collect(Collectors.toMap(Map.Entry::getKey, Sessions::soretdEntries, throwingMerger(), TreeMap::new));
   }

   private static Stream<Map.Entry<String, Client.MinMax>> soretdEntries(Map.Entry<String, Map<String, Client.MinMax>> e) {
      return e.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey());
   }

   private static BinaryOperator<Stream<Map.Entry<String, Client.MinMax>>> throwingMerger() {
      return (u, v) -> { throw new IllegalStateException(); };
   }
}
