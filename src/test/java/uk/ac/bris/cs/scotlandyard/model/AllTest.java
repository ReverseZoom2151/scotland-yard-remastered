package uk.ac.bris.cs.scotlandyard.model;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Includes all test for the actual game model
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
		GameStateCreationTest.class,
		GameStateGameOverTest.class,
		GameStateRoundTest.class,
		GameStatePlayerTest.class,
		GameStateDetectivesAvailableMovesTest.class,
		GameStateMrXAvailableMovesTest.class,
		GameStatePlayoutTest.class,
		GameStartIsPlayableTest.class,
		ModelObserverTest.class,
		// Surefire's includes are pinned to **/AllTest.class and the ui/ai suites, so a
		// test in auxiliary/ that is not named here never runs at all — as the graph
		// reader's seven tests did not, for the life of the project.
		uk.ac.bris.cs.scotlandyard.auxiliary.ScotlandYardGraphReaderTest.class,
		uk.ac.bris.cs.scotlandyard.auxiliary.TinyMapTest.class
})
public class AllTest {}
