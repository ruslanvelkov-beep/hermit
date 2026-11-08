package hermit.plan;

import static org.junit.jupiter.api.Assertions.*;

import hermit.api.Action;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

public class BranchSelectorTest {

    private final BranchSelector branchingEngine = new BranchSelector();

    /**
     * Test case for when the input list of options is null.
     */
    @Test
    void selectBestBranch_whenOptionsIsNull_returnsNull()
    {
        assertNull(branchingEngine.selectBest(null), 
            "Selecting a null list should return null.");
    }

    /**
     * Test case for when the input list of options is empty.
     */
    @Test
    void selectBestBranch_whenOptionsIsEmpty_returnsNull()
    {
        List<Branch> emptyList = Collections.emptyList();
        assertNull(branchingEngine.selectBest(emptyList), 
            "Selecting an empty list should return null.");
    }

    /**
     * Test case for a standard scenario where one branch clearly has the highest confidence.
     */
    @Test
    void selectBestBranch_returnsHighestConfidenceBranch()
    {
        // Branch A: Confidence 0.5
        Branch branchA = new Branch("A", "Low confidence option", 0.5, Collections.emptyList());
        // Branch B: Confidence 0.9 (Highest)
        Branch branchB = new Branch("B", "High confidence option", 0.9, Collections.emptyList());
        // Branch C: Confidence 0.8
        Branch branchC = new Branch("C", "Medium confidence option", 0.8, Collections.emptyList());

        List<Branch> options = Arrays.asList(branchA, branchB, branchC);

        Branch bestBranch = branchingEngine.selectBest(options);

        assertNotNull(bestBranch, "The result should not be null for a non-empty list.");
        assertEquals("B", bestBranch.name(), "The branch with the highest confidence (0.9) should be selected.");
    }

    /**
     * Test case for a scenario where all branches have the same confidence (testing tie behavior).
     */
    @Test
    void selectBestBranch_returnsOneOfTiedBranches()
    {
        // Branch X: Confidence 0.7
        Branch branchX = new Branch("X", "Tied option 1", 0.7, Collections.emptyList());
        // Branch Y: Confidence 0.7
        Branch branchY = new Branch("Y", "Tied option 2", 0.7, Collections.emptyList());

        List<Branch> options = Arrays.asList(branchX, branchY);

        Branch bestBranch = branchingEngine.selectBest(options);

        assertNotNull(bestBranch, "The result should not be null.");
        // Since the implementation uses max() on a stream, either X or Y is acceptable if they tie. 
        // We just verify that one of the provided branches was returned.
        assertTrue(bestBranch == branchX || bestBranch == branchY, 
            "One of the tied branches should be returned.");
    }

    /**
     * Test case for a list containing only one branch.
     */
    @Test
    void selectBestBranch_returnsSingleBranchWhenOnlyOneExists()
    {
        Branch singleBranch = new Branch("Single", "Only option", 1.0, Collections.emptyList());
        List<Branch> options = Collections.singletonList(singleBranch);

        Branch bestBranch = branchingEngine.selectBest(options);

        assertNotNull(bestBranch, "The single branch should be selected.");
        assertEquals("Single", bestBranch.name());
    }
}
