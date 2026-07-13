<h1 align="center">Scotland Yard Remastered</h1>

<p align="center">
Five detectives hunt Mr X across the 199 stations of the London Underground. He moves in
secret, surfacing only on the reveal rounds. The rest of the time all anyone knows is which
kind of ticket he spent.
</p>

<p align="center">
<b>That ticket is the whole game, and it is the whole point of this repository.</b>
</p>

<p align="center">
  <img src="docs/screenshot.png" alt="Scotland Yard" width="900">
</p>

## About

A taxi ticket can only have carried Mr X down a taxi edge. So the travel log, which records
the *kind* of ticket he spent but not the station, quietly constrains where he can be. Seed
that at his last reveal, expand it once per logged ticket, and you get the set of stations he
could be standing on right now. A secret ticket crosses any edge, so it tells you nothing,
which is exactly what makes it worth holding.

This repository is built around that idea. The AI is never handed Mr X's position: `Board` has
no `getMrXLocation()`, so the detective side has to work it out, and Mr X in turn has to reason
about what they can work out. The same inference is painted on the board as a heatmap, so a
human detective can see the net closing too.

The game itself is a University of Bristol coursework framework. It arrived throwing a
`NullPointerException` on game creation, so nothing ran at all. What grew out of fixing that:

- an alpha-beta AI with iterative deepening, playing both sides
- a **headless arena** that plays AI against AI in parallel and reports win rates, because
  otherwise every claim about AI strength is just an opinion
- a **suspicion overlay** rendering the detectives' belief about Mr X, live
- save, load, replay and undo, which are all the same fold over an immutable model
- a **QR private-move channel**, so Mr X can take his turn on a shared screen without the
  detectives reading it over his shoulder

199 tests. The detective side of the AI is the stronger one; Mr X is still the weaker half, and
the work on him is unfinished. The README says so below rather than pretending otherwise.

## Running it

Requires JDK 17. Maven is not needed, because the wrapper fetches it.

```bash
./mvnw test                            # 199 tests
./mvnw exec:java@game                  # launch the game
```

On Windows use `mvnw.cmd`. If Maven reports `JAVA_HOME not found`, point it at your JDK:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
```

Note that `exec:java` does not recompile. Run `./mvnw compile` first if you have changed
anything.

## How the game is modelled

The board is a Guava `ImmutableValueGraph<Integer, ImmutableSet<Transport>>`. Stations are
nodes, and each edge carries the set of transports that run along it. A ferry edge can only
be crossed with a secret ticket.

Two classes carry the game logic. Everything else is provided framework.

`MyGameStateFactory` builds a `GameState`, an immutable snapshot of a game in progress. Each
state derives its available moves, its winner and its player set once, in the constructor,
and caches them. `advance(Move)` does not mutate anything, it returns a new state. That
matters for the AI, where a search holds thousands of states at once and calls
`getAvailableMoves()` in its innermost loop.

`MyModelFactory` wraps a `GameState` in an observable `Model`. Observers are notified after
the state has advanced, with `GAME_OVER` in place of `MOVE_MADE` on the final move.

Some rules worth stating, because they are easy to get subtly wrong:

- A secret ticket substitutes for any transport, ferries included, so a secret move exists
  for every edge out of a station.
- A double move spans two rounds. It needs two rounds left in the log, and each leg is
  written to the travel log against its own round, so a double move straddling a reveal
  round is hidden for one leg and revealed for the other. Its second ticket is only
  affordable once the first has been spent.
- A detective hands the ticket it spends to Mr X as it moves. A detective late in a rotation
  can therefore un-strand a Mr X who had no ticket to move with.
- A detective is out of the game once it has no legal move. That is weaker than holding no
  tickets, since it can hold a full hand and still be boxed in by its own teammates. When
  that happens it is skipped and play passes back to Mr X.
- Detectives win by landing on Mr X. Mr X wins by filling the travel log, or by stranding
  every detective. Once anyone has won there are no available moves.

## Layout

```
src/main/java/uk/ac/bris/cs/scotlandyard/
  model/          game logic. MyGameStateFactory and MyModelFactory are the implementation,
                  the rest (Board, Move, Player, ScotlandYard, Ai) is framework
  ui/             JavaFX views and controllers
  ui/ai/          the AI players
  ui/ai/arena/    headless AI-vs-AI batches
  ui/privacy/     the QR private move channel
  persistence/    save, load, replay, undo
src/main/resources/
  graph.txt       the 199-station map, 467 edges
  tiny-graph.txt  a 20-station map, for fast sweeps and exact endgame tests
  pos.txt         station coordinates for the board image
src/test/         199 tests
```

## AI players

`ResourceManager.scanAis()` scans the classpath at startup, so an AI is a drop-in. Any public
class with a no-arg constructor implementing `Ai` is found automatically and offered in the
setup screen.

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
`Board` has no `getMrXLocation()`.

That second one is the real problem, and `ui/ai/` is an answer to it.

| Class | Role |
|---|---|
| `MrXLocator` | Infers where Mr X is, for the detective side. |
| `BoardStates` | Rebuilds an advanceable `GameState` from a `Board`. |
| `Distances` | All-pairs hops, precomputed, plus a ticket-aware distance. |
| `Evaluator` | Scores a position from Mr X's point of view. |
| `Search` | Alpha-beta, deepened until the clock runs out. |
| `MyAi` | Ties it together. |

**Finding Mr X.** The travel log names a station only on the reveal rounds, but between them
it still records the kind of ticket he spent, and that is the leak. A taxi ticket can only
have carried him down a taxi edge. So the candidate set is seeded at his last reveal and
expanded once per logged entry, along the edges that ticket could have paid for. Candidates
standing on a detective are pruned, because he would have been caught there. A secret ticket
crosses any edge, so it expands everywhere and tells you nothing, which is what makes it
worth holding.

**Scoring.** Three factors: distance to the detectives, weighted toward the nearest one since
that is what catches you; freedom, meaning how many onward moves the station leaves; and
safety, meaning whether a detective can reach it next move. They multiply rather than add, so
a station that is catastrophic on any single axis is vetoed instead of being averaged back
into respectability. Secret and double tickets carry a price, so Mr X spends them to escape
rather than idly.

**Searching.** Iterative deepening against the real deadline. A ply only replaces the
incumbent move once it has finished, so the search is always safe to interrupt. Detectives
are modelled greedily rather than branched, because every detective moving in every
combination explodes the tree, and depth buys more than an exact reply does.

## The arena

Win rates settle arguments that opinions cannot. The arena plays AI against AI headlessly,
in parallel, from reproducible seeds.

```bash
./mvnw compile                      # exec:java does not recompile
./mvnw exec:java@arena -Dexec.args="--games=100 --mrx=MyAi --detectives=RandomAi"
```

It records more than who won: the round of every secret and double Mr X spends, the round he
was caught, and the size and entropy of the detectives' belief at the end. That is what
distinguishes a change that worked from a change that got lucky, and it is the more useful
half of the tool. A win rate that moves for the wrong reason is worse than one that does not
move at all, because you will believe it.

No standings are published here yet. The AI is mid-experiment and the sample sizes so far are
too small to draw from: at 100 games a win rate carries a standard deviation of about five
points, which is wider than most of the differences worth arguing about. Run it yourself if
you want a number today.

## Other things it does

Three board overlays, from the View menu. **Suspicion** paints every station Mr X could be
standing on, inferred from the public travel log alone, shaded by likelihood. It reads the
board, never the AI, so it works when a human is playing Mr X. **Ambiguity** shades each
station by how many others it reaches in two moves, which is where Mr X most wants to be.
**Explain AI** shows the moves the AI considered and what it thought of them.

**Save, load, replay and undo** are one feature. The model is immutable and `advance()` is a
fold, so a game is fully described by its setup, its opening players and its move list. Load
is the fold. Replay is the fold with a delay. Undo is the fold, one ply shorter.

**Private Mr X moves.** Hot seat means Mr X's move is not really secret, because everyone is
looking at the same screen. A QR code carries a shuffled letter-to-destination table to his
phone and the screen shows only letters.

## Known gaps

Mr X is the weak half, and the work to fix him is unfinished. He handles a weak opponent, but
he loses the mirror match against his own detectives, and he survives fewer rounds against
himself than the far simpler `GreedyAi` manages. Making his search model the detectives as
chasing his *inferred* position rather than his real one was supposed to fix that. It helped a
little and did not fix it, and the reason is not yet understood. That is a question for the
arena, not for another round of tuning constants.

Hot-seat still leaks Mr X's current position: the board reveals his counter on his own turn,
so the QR channel hides where he is going but not where he is.

The ticket budget in `Distances.ticketAwareDistance` is an approximation, said plainly in the
Javadoc rather than papered over.
