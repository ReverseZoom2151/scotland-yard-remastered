# Scotland Yard

A Java implementation of the Scotland Yard board game, played on the real 199-station
London map. Five detectives hunt Mr X across the city; Mr X moves in secret, surfacing
only on the reveal rounds.

![Scotland Yard](https://github.com/user-attachments/assets/590211c7-c7bd-437d-9acf-a6c667a2e43f)

## Running it

Requires **JDK 17**. Maven is not needed — the wrapper fetches it.

```bash
./mvnw test                            # 83 tests
./mvnw exec:java                       # launch the game
```

On Windows use `mvnw.cmd`. If Maven reports `JAVA_HOME not found`, point it at your JDK:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
```

## How the game is modelled

The board is a Guava `ImmutableValueGraph<Integer, ImmutableSet<Transport>>`: stations are
nodes, and each edge carries the set of transports that run along it. A ferry edge is
crossed only with a secret ticket.

Two classes carry the game logic; everything else is provided framework.

**`MyGameStateFactory`** builds a `GameState` — an immutable snapshot of a game in
progress. Each state derives its available moves, its winner and its player set once, in
the constructor, and caches them. `advance(Move)` doesn't mutate anything; it returns a
new state. That matters for the AI stage, where a search holds thousands of states at once
and calls `getAvailableMoves()` in its innermost loop.

**`MyModelFactory`** wraps a `GameState` in an observable `Model`. Observers are notified
after the state has advanced, with `GAME_OVER` in place of `MOVE_MADE` on the final move.

A few rules that the implementation turns on, and that are easy to get subtly wrong:

- A **secret ticket substitutes for any transport**, ferries included, so a secret move
  exists for every edge out of a station.
- A **double move spans two rounds**, so it needs two rounds left in the log, and each leg
  is written to the travel log against *its own* round — a double move straddling a reveal
  round is hidden for one leg and revealed for the other. Its second ticket is only
  affordable if the first has already been spent.
- A detective **hands the ticket it spends to Mr X**, as it moves. A detective late in a
  rotation can therefore un-strand a Mr X who had no ticket to move with.
- A detective is out of the game once it has **no legal move** — which is weaker than
  holding no tickets, since it can hold a full hand and still be boxed in by its own
  teammates. When that happens it is skipped, and play passes back to Mr X.
- Detectives win by **landing on Mr X**. Mr X wins by filling the travel log, or by
  stranding every detective. Once anyone has won there are no available moves.

## Layout

```
src/main/java/uk/ac/bris/cs/scotlandyard/
  model/          game logic — MyGameStateFactory and MyModelFactory are the implementation,
                  the rest (Board, Move, Player, ScotlandYard, Ai, …) is framework
  ui/             JavaFX views and controllers
src/main/resources/
  graph.txt       the 199-station map, 467 edges
  pos.txt         station coordinates for the board image
src/test/         83 tests; AllTest is the suite the build runs
```

## AI players

`ResourceManager.scanAis()` scans the classpath at startup, so an AI is a drop-in: any
public class with a no-arg constructor implementing `Ai` is found automatically and offered
in the setup screen.

```java
public interface Ai {
    String name();
    Move pickMove(Board board, Pair<Long, TimeUnit> timeoutPair);
    default void onStart() {}
    default void onTerminate() {}
}
```

Two constraints shape any implementation. `pickMove` runs against the timeout it is handed
and is killed one second after it expires, so a search needs a hard internal deadline. And
`Board` deliberately **has no `getMrXLocation()`** — a detective AI has to infer where Mr X
is from the travel log, which only names a station on the reveal rounds (3, 8, 13, 18, 24),
propagating outward through the ticket types he logged in between.

**No AI is implemented yet.** The interface and its discovery mechanism are in place.
