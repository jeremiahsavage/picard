package picard.sam;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMFileWriterFactory;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordSetBuilder;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.util.IOUtil;
import org.testng.Assert;
import org.testng.annotations.Test;
import picard.cmdline.CommandLineProgramTest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Tests related to code in AbstractAlignmentMerger
 */
public class AbstractAlignmentMergerTest extends CommandLineProgramTest {
    @Test public void tesOverlappedReadClippingWithNonOverlappedReads() {
        final SAMRecordSetBuilder set = new SAMRecordSetBuilder();
        set.setReadLength(110);
        final List<SAMRecord> recs = set.addPair("q1", 0, 100, 200, false, false, "110M", "110M", false, true, 30);
        final SAMRecord r1 = recs.get(0);
        final SAMRecord r2 = recs.get(1);
        AbstractAlignmentMerger.clipForOverlappingReads(r1, r2);
        Assert.assertEquals(r1.getAlignmentStart(), 100);
        Assert.assertEquals(r1.getCigarString(), "110M");
        Assert.assertEquals(r2.getAlignmentStart(), 200);
        Assert.assertEquals(r2.getCigarString(), "110M");
    }

    @Test public void testBasicOverlappedReadClipping() {
        final SAMRecordSetBuilder set = new SAMRecordSetBuilder();
        set.setReadLength(110);
        final List<SAMRecord> recs = set.addPair("q1", 0, 100, 90, false, false, "110M", "110M", false, true, 30);
        final SAMRecord r1 = recs.get(0);
        final SAMRecord r2 = recs.get(1);
        AbstractAlignmentMerger.clipForOverlappingReads(r1, r2);
        Assert.assertEquals(r1.getAlignmentStart(), 100);
        Assert.assertEquals(r1.getCigarString(), "100M10S");
        Assert.assertEquals(r2.getAlignmentStart(), 100);
        Assert.assertEquals(r2.getCigarString(), "10S100M");
    }

    @Test public void testOverlappedReadClippingWithExistingSoftClipping() {
        final SAMRecordSetBuilder set = new SAMRecordSetBuilder();
        set.setReadLength(120);
        final List<SAMRecord> recs = set.addPair("q1", 0, 100, 95, false, false, "110M10S", "15S105M", false, true, 30);
        final SAMRecord r1 = recs.get(0);
        final SAMRecord r2 = recs.get(1);
        AbstractAlignmentMerger.clipForOverlappingReads(r1, r2);
        Assert.assertEquals(r1.getAlignmentStart(), 100);
        Assert.assertEquals(r1.getCigarString(), "100M20S");
        Assert.assertEquals(r2.getAlignmentStart(), 100);
        Assert.assertEquals(r2.getCigarString(), "20S100M");
    }

    @Test public void testOverlappedReadClippingWithExistingSoftClippingAndHardClipping() {
        final SAMRecordSetBuilder set = new SAMRecordSetBuilder();
        set.setReadLength(120);
        final List<SAMRecord> recs = set.addPair("q1", 0, 100, 95, false, false, "110M10S5H", "5H15S105M", false, true, 30);
        final SAMRecord r1 = recs.get(0);
        final SAMRecord r2 = recs.get(1);
        AbstractAlignmentMerger.clipForOverlappingReads(r1, r2);
        Assert.assertEquals(r1.getAlignmentStart(), 100);
        Assert.assertEquals(r1.getCigarString(), "100M20S"); // Should ideally be 100M20S5H
        Assert.assertEquals(r2.getAlignmentStart(), 100);
        Assert.assertEquals(r2.getCigarString(), "20S100M"); // Should ideally be 5H20S100M
    }


    @Test
    public void testUnmapBacterialContamination() throws IOException {
        final SAMRecordSetBuilder builder = new SAMRecordSetBuilder();
        builder.getHeader().setSortOrder(SAMFileHeader.SortOrder.unsorted);

        builder.addPair("overlappingpair", 0,500,500, false,false,"20S20M60S","20S20M60M",true,false,45);
        builder.addPair("overlappingpairFirstAligned", 0,500,500, false,true,"20S20M60S",null,true,false,45);
        builder.addPair("overlappingpairSecondAligned", 0,500,500, true,false,null,"20S20M60S",true,false,45);
        builder.addPair("overlappingpairFirstAlignedB", 0,500,500, false,true,"20S20M60S",null,false,true,45);
        builder.addPair("overlappingpairSecondAlignedB", 0,500,500, true,false,null,"20S20M60S",false,true,45);

        builder.addFrag("frag",1,500,false,false,"20S20M60S",null, 45);
        builder.addFrag("frag2",1,500,true,false,"20S20M60S",null, 45);
        builder.addFrag("frag3",1,500,false,true,"20S20M60S",null, 45);
        builder.addFrag("frag4",1,500,true,true,"20S20M60S",null, 45);

        final File file = newTempSamFile("aligned");

        try(SAMFileWriter writer = new SAMFileWriterFactory().makeWriter(builder.getHeader(), false, file, null)){
            builder.getRecords().forEach(writer::addAlignment);
        }

        final RevertSam revertSam = new RevertSam();

        revertSam.INPUT = file;
        final File fileUnaligned = newTempSamFile("unaligned");
        revertSam.OUTPUT = fileUnaligned;

        revertSam.SANITIZE = true;
        revertSam.SORT_ORDER = SAMFileHeader.SortOrder.unsorted;

        Assert.assertEquals(revertSam.doWork(),0);

        MergeBamAlignment mergeBamAlignment = new MergeBamAlignment();

        mergeBamAlignment.ALIGNED_BAM = Collections.singletonList(file);
        mergeBamAlignment.UNMAPPED_BAM = fileUnaligned;
        mergeBamAlignment.UNMAP_CONTAMINANT_READS = true;

        final File fileMerged = newTempSamFile("merged");

        mergeBamAlignment.OUTPUT = fileMerged;

        // merge file with itself.
        Assert.assertEquals(mergeBamAlignment.doWork(),0);

        // check that all reads have been unmapped due to bacterial contamination as needed.
        try(SamReader mergedReader = SamReaderFactory.makeDefault().open(fileMerged)) {

            for (SAMRecord samRecord : mergedReader) {
                Assert.assertTrue(samRecord.getReadUnmappedFlag());
                Assert.assertTrue(samRecord.getMateUnmappedFlag());
                Assert.assertEquals(samRecord.getAttribute("CO"), "Cross-species contamination");
            }
        }
    }

    @Override
    public String getCommandLineProgramName() {
        return this.getClass().getSimpleName();
    }


    private static File newTempSamFile(final String filename) throws IOException {
        final File file = File.createTempFile(filename,".sam");
        file.deleteOnExit();
        return file;
    }
}

