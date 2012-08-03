/*
 * Copyright (c) 2011 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.walkers.variantrecalibration;

import org.broadinstitute.sting.commandline.Argument;
import org.broadinstitute.sting.commandline.Input;
import org.broadinstitute.sting.commandline.Output;
import org.broadinstitute.sting.commandline.RodBinding;
import org.broadinstitute.sting.gatk.CommandLineGATK;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.contexts.ReferenceContext;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.walkers.PartitionBy;
import org.broadinstitute.sting.gatk.walkers.PartitionType;
import org.broadinstitute.sting.gatk.walkers.RodWalker;
import org.broadinstitute.sting.gatk.walkers.TreeReducible;
import org.broadinstitute.sting.utils.SampleUtils;
import org.broadinstitute.sting.utils.codecs.vcf.*;
import org.broadinstitute.sting.utils.help.DocumentedGATKFeature;
import org.broadinstitute.sting.utils.variantcontext.writer.VariantContextWriter;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.variantcontext.VariantContext;
import org.broadinstitute.sting.utils.variantcontext.VariantContextBuilder;

import java.io.File;
import java.util.*;

/**
 * Applies cuts to the input vcf file (by adding filter lines) to achieve the desired novel truth sensitivity levels which were specified during VariantRecalibration
 *
 * <p>
 * Using the tranche file generated by the previous step the ApplyRecalibration walker looks at each variant's VQSLOD value
 * and decides which tranche it falls in. Variants in tranches that fall below the specified truth sensitivity filter level
 * have their filter field annotated with its tranche level. This will result in a call set that simultaneously is filtered
 * to the desired level but also has the information necessary to pull out more variants for a higher sensitivity but a
 * slightly lower quality level.
 *
 * <h2>Input</h2>
 * <p>
 * The input raw variants to be recalibrated.
 * <p>
 * The recalibration table file in VCF format that was generated by the VariantRecalibrator walker.
 * <p>
 * The tranches file that was generated by the VariantRecalibrator walker.
 *
 * <h2>Output</h2>
 * <p>
 * A recalibrated VCF file in which each variant is annotated with its VQSLOD and filtered if the score is below the desired quality level.
 *
 * <h2>Examples</h2>
 * <pre>
 * java -Xmx3g -jar GenomeAnalysisTK.jar \
 *   -T ApplyRecalibration \
 *   -R reference/human_g1k_v37.fasta \
 *   -input NA12878.HiSeq.WGS.bwa.cleaned.raw.subset.b37.vcf \
 *   --ts_filter_level 99.0 \
 *   -tranchesFile path/to/output.tranches \
 *   -recalFile path/to/output.recal \
 *   -mode SNP \
 *   -o path/to/output.recalibrated.filtered.vcf
 * </pre>
 *
 */

@DocumentedGATKFeature( groupName = "Variant Discovery Tools", extraDocs = {CommandLineGATK.class} )
@PartitionBy(PartitionType.LOCUS)
public class ApplyRecalibration extends RodWalker<Integer, Integer> implements TreeReducible<Integer> {

    /////////////////////////////
    // Inputs
    /////////////////////////////
    /**
     * These calls should be unfiltered and annotated with the error covariates that are intended to use for modeling.
     */
    @Input(fullName="input", shortName = "input", doc="The raw input variants to be recalibrated", required=true)
    public List<RodBinding<VariantContext>> input;
    @Input(fullName="recal_file", shortName="recalFile", doc="The input recal file used by ApplyRecalibration", required=true)
    protected RodBinding<VariantContext> recal;
    @Input(fullName="tranches_file", shortName="tranchesFile", doc="The input tranches file describing where to cut the data", required=true)
    protected File TRANCHES_FILE;

    /////////////////////////////
    // Outputs
    /////////////////////////////
    @Output( doc="The output filtered and recalibrated VCF file in which each variant is annotated with its VQSLOD value", required=true)
    private VariantContextWriter vcfWriter = null;

    /////////////////////////////
    // Command Line Arguments
    /////////////////////////////
    @Argument(fullName="ts_filter_level", shortName="ts_filter_level", doc="The truth sensitivity level at which to start filtering", required=false)
    protected double TS_FILTER_LEVEL = 99.0;
    @Argument(fullName="ignore_filter", shortName="ignoreFilter", doc="If specified the variant recalibrator will use variants even if the specified filter name is marked in the input VCF file", required=false)
    private String[] IGNORE_INPUT_FILTERS = null;
    @Argument(fullName = "mode", shortName = "mode", doc = "Recalibration mode to employ: 1.) SNP for recalibrating only SNPs (emitting indels untouched in the output VCF); 2.) INDEL for indels; and 3.) BOTH for recalibrating both SNPs and indels simultaneously.", required = false)
    public VariantRecalibratorArgumentCollection.Mode MODE = VariantRecalibratorArgumentCollection.Mode.SNP;

    /////////////////////////////
    // Private Member Variables
    /////////////////////////////
    final private List<Tranche> tranches = new ArrayList<Tranche>();
    final private Set<String> inputNames = new HashSet<String>();
    final private Set<String> ignoreInputFilterSet = new TreeSet<String>();

    //---------------------------------------------------------------------------------------------------------------
    //
    // initialize
    //
    //---------------------------------------------------------------------------------------------------------------

    public void initialize() {
        for ( final Tranche t : Tranche.readTranches(TRANCHES_FILE) ) {
            if ( t.ts >= TS_FILTER_LEVEL ) {
                tranches.add(t);
            }
            logger.info(String.format("Read tranche " + t));
        }
        Collections.reverse(tranches); // this algorithm wants the tranches ordered from best (lowest truth sensitivity) to worst (highest truth sensitivity)

        for( final RodBinding rod : input ) {
            inputNames.add( rod.getName() );
        }

        if( IGNORE_INPUT_FILTERS != null ) {
            ignoreInputFilterSet.addAll( Arrays.asList(IGNORE_INPUT_FILTERS) );
        }

        // setup the header fields
        final Set<VCFHeaderLine> hInfo = new HashSet<VCFHeaderLine>();
        hInfo.addAll(VCFUtils.getHeaderFields(getToolkit(), inputNames));
        addVQSRStandardHeaderLines(hInfo);
        final TreeSet<String> samples = new TreeSet<String>();
        samples.addAll(SampleUtils.getUniqueSamplesFromRods(getToolkit(), inputNames));

        if( tranches.size() >= 2 ) {
            for( int iii = 0; iii < tranches.size() - 1; iii++ ) {
                final Tranche t = tranches.get(iii);
                hInfo.add(new VCFFilterHeaderLine(t.name, String.format("Truth sensitivity tranche level for " + t.model.toString() + " model at VQS Lod: " + t.minVQSLod + " <= x < " + tranches.get(iii+1).minVQSLod)));
            }
        }
        if( tranches.size() >= 1 ) {
            hInfo.add(new VCFFilterHeaderLine(tranches.get(0).name + "+", String.format("Truth sensitivity tranche level for " + tranches.get(0).model.toString() + " model at VQS Lod < " + tranches.get(0).minVQSLod)));
        } else {
            throw new UserException("No tranches were found in the file or were above the truth sensitivity filter level " + TS_FILTER_LEVEL);
        }

        logger.info("Keeping all variants in tranche " + tranches.get(tranches.size()-1));

        final VCFHeader vcfHeader = new VCFHeader(hInfo, samples);
        vcfWriter.writeHeader(vcfHeader);
    }

    public static void addVQSRStandardHeaderLines(final Set<VCFHeaderLine> hInfo) {
        hInfo.add(VCFStandardHeaderLines.getInfoLine(VCFConstants.END_KEY));
        hInfo.add(new VCFInfoHeaderLine(VariantRecalibrator.VQS_LOD_KEY, 1, VCFHeaderLineType.Float, "Log odds ratio of being a true variant versus being false under the trained gaussian mixture model"));
        hInfo.add(new VCFInfoHeaderLine(VariantRecalibrator.CULPRIT_KEY, 1, VCFHeaderLineType.String, "The annotation which was the worst performing in the Gaussian mixture model, likely the reason why the variant was filtered out"));
    }

    //---------------------------------------------------------------------------------------------------------------
    //
    // map
    //
    //---------------------------------------------------------------------------------------------------------------

    public Integer map( RefMetaDataTracker tracker, ReferenceContext ref, AlignmentContext context ) {

        if( tracker == null ) { // For some reason RodWalkers get map calls with null trackers
            return 1;
        }

        final List<VariantContext> VCs =  tracker.getValues(input, context.getLocation());
        final List<VariantContext> recals =  tracker.getValues(recal, context.getLocation());

        for( final VariantContext vc : VCs ) {

            if( VariantDataManager.checkVariationClass( vc, MODE ) && (vc.isNotFiltered() || ignoreInputFilterSet.containsAll(vc.getFilters())) ) {

                final VariantContext recalDatum = getMatchingRecalVC(vc, recals);
                if( recalDatum == null ) {
                    throw new UserException("Encountered input variant which isn't found in the input recal file. Please make sure VariantRecalibrator and ApplyRecalibration were run on the same set of input variants. First seen at: " + vc );
                }

                final String lodString = recalDatum.getAttributeAsString(VariantRecalibrator.VQS_LOD_KEY, null);
                if( lodString == null ) {
                    throw new UserException("Encountered a malformed record in the input recal file. There is no lod for the record at: " + vc );
                }
                final double lod;
                try {
                    lod = Double.valueOf(lodString);
                } catch (NumberFormatException e) {
                    throw new UserException("Encountered a malformed record in the input recal file. The lod is unreadable for the record at: " + vc );
                }

                VariantContextBuilder builder = new VariantContextBuilder(vc);
                String filterString = null;

                // Annotate the new record with its VQSLOD and the worst performing annotation
                builder.attribute(VariantRecalibrator.VQS_LOD_KEY, lodString); // use the String representation so that we don't lose precision on output
                builder.attribute(VariantRecalibrator.CULPRIT_KEY, recalDatum.getAttribute(VariantRecalibrator.CULPRIT_KEY));

                for( int i = tranches.size() - 1; i >= 0; i-- ) {
                    final Tranche tranche = tranches.get(i);
                    if( lod >= tranche.minVQSLod ) {
                        if( i == tranches.size() - 1 ) {
                            filterString = VCFConstants.PASSES_FILTERS_v4;
                        } else {
                            filterString = tranche.name;
                        }
                        break;
                    }
                }

                if( filterString == null ) {
                    filterString = tranches.get(0).name+"+";
                }

                if( filterString.equals(VCFConstants.PASSES_FILTERS_v4) ) {
                    builder.passFilters();
                } else {
                    builder.filters(filterString);
                }

                vcfWriter.add( builder.make() );
            } else { // valid VC but not compatible with this mode, so just emit the variant untouched
                vcfWriter.add( vc );
            }
        }

        return 1; // This value isn't used for anything
    }

    private static VariantContext getMatchingRecalVC(final VariantContext target, final List<VariantContext> recalVCs) {
        for( final VariantContext recalVC : recalVCs ) {
            if ( target.getEnd() == recalVC.getEnd() ) {
                return recalVC;
            }
        }

        return null;
    }

    //---------------------------------------------------------------------------------------------------------------
    //
    // reduce
    //
    //---------------------------------------------------------------------------------------------------------------

    public Integer reduceInit() {
        return 1; // This value isn't used for anything
    }

    public Integer reduce( final Integer mapValue, final Integer reduceSum ) {
        return 1; // This value isn't used for anything
    }

    public Integer treeReduce( final Integer lhs, final Integer rhs ) {
        return 1; // This value isn't used for anything
    }

    public void onTraversalDone( final Integer reduceSum ) {
    }
}

