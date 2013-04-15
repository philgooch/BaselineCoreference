/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.philgooch;

import gate.*;
import gate.creole.*;
import gate.creole.metadata.*;
import gate.util.*;

import gate.Annotation;
import gate.AnnotationSet;
import gate.Document;
import gate.Factory;
import gate.FeatureMap;

import com.wcohen.ss.*;
import com.wcohen.ss.api.*;

import java.util.regex.Pattern;
import java.util.*;
import java.io.*;
import java.net.*;

/**
 *
 * @author philipgooch
 */
@CreoleResource(name = "BaselineCoreference",
helpURL = "",
comment = "Calculates the string similarity between pairs of input annotations using a variety of available metrics.")
public class BaselineCoreference extends AbstractLanguageAnalyser implements
        ProcessingResource,
        Serializable {

    private String inputASName;     //  Input AnnotationSet name
    private String outputASName;    // Output AnnotationSet set name
    private ArrayList<String> inputASTypes; // list of input annotations from which string content will be taken for input 
    private ArrayList<String> excludeIfWithin;      // don't process if inputASType occurs inside one of these annots
    private ArrayList<String> comparisonFeats;      // List of features within inputASTypes that will be compared
    private ArrayList<String> matchingFeats;      // List of features that appear on both antecedent and anaphor and must match
    
    private ArrayList<String> annFeatsToSortal;      // List of features that map to a similar content string in a sortal anaphor, e.g. {Location type=city} could form a bridging coref with 'that city'
    private ArrayList<String> annFeatsToContent;      // List of antecedent features can match anaphor string, e.g. synonym -> string
    
    private ArrayList<String> featsClone;      // List of features that should be cloned from antecedent to anaphor

    private String corefIdFeature;              // Feature that will hold coref id
    private String backrefIdFeature;            // Feature that will hold backref id
    private String backrefTextFeature;            // Feature that will hold antecedent backref text
    private String corefTextFeature;          // Feature that will hold coreferring text

    private Boolean annTypeToSortal;                 // Flag to determine whether to form bridging coref from annotation type to sortal anaphor, e.g. {Location} could form a bridging coref with 'this location'
    private String contentFeature;       // Feature that holds the string content of each of inputASTypes, or use annotation content if not specified

    private Integer maxNominalSentenceDistance;              // Maximum distance in sentences between nominal coreferencing pairs
    private Integer maxSortalSentenceDistance;              // Maximum distance in sentences between sortal coreferencing pairs, e.g. 'hysterectomy' and 'the operation'
    private String sentenceName;               // default to Sentence
    private String tokenName;                   // default to Token

    private Integer shortestWord;       // ignore words under this threshold
    private Double stringMatchThreshold;     // threshold string similarity score
    private Double featureMatchThreshold;     // fraction of total features that must match

    private SimilarityMeasure similarityMeasure1;        // Main similarity measure
    private SimilarityMeasure similarityMeasure2;        // Secondary similarity measure
    private MeasureCompare similarityComparison;        // max, mean or min of main and secondary measures

    // Exit gracefully if exception caught on init()
    private boolean gracefulExit;

    private static final String definiteDescriptorRegEx = "(?i)the|this|that|these|those|his|her|their|its|your|our";
    private static final String wordBreakRegEx = "([\\s\\xA0]+)|([^a-zA-Z_0-9\\-]+)";

    // Output Lists as strings or as a List object
    public enum SimilarityMeasure {
        None, Jaro, JaroWinkler, Jaccard, Levenstein, MongeElkan,
        Level2Jaro, Level2JaroWinkler, Level2Levenstein, Level2MongeElkan
    }

    public enum MeasureCompare {
        mean, max, min
    }


    /**
     *
     * @param expression    an expression of the form Annotation or Annotation.feature == value
     * @return              string containing the annotation name from the input expression
     */
    private String getAnnNameFromExpression(String expression) {
        String[] inputAnnArr = expression.split("\\s*==\\s*");
        return inputAnnArr[0];
    }

    /**
     *
     * @param inputAS           some input Annotation Set
     * @param inputAnnExpr      some Annotation or Annotation.feature == value expression
     * @return  inputAS filtered according to inputAnnExpr
     */
    private AnnotationSet getFilteredAS(AnnotationSet inputAS, String inputAnnExpr) {
        // We allow inputAnnExpr of the form
        // Annotation or Annotation.feature == value
        String annFeature;
        String annFeatureValue;

        // Assume a simple ann name unless we have a feature and feature value present
        AnnotationSet filteredAS = inputAS.get(inputAnnExpr);

        // Check if we have an expression of the form Annotation.feature == value
        String[] inputAnnArr = inputAnnExpr.split("\\s*==\\s*");

        if (inputAnnArr.length == 2) {
            String base = inputAnnArr[0];
            int dot = base.lastIndexOf(".");
            if (dot > 0 && dot < base.length() - 1) {
                String annName = base.substring(0, dot);
                annFeature = base.substring(dot + 1);
                annFeatureValue = inputAnnArr[1];
                FeatureMap annFeats = Factory.newFeatureMap();
                annFeats.put(annFeature, annFeatureValue);
                filteredAS = inputAS.get(annName, annFeats);
            }
        }
        return filteredAS;
    }

    /**
     *
     * @param inputAS           input Annotation Set
     * @param currStart         annotation start offset
     * @param currEnd           annotation end offset
     * @return                  true if annotation occurs within exclusion region
     */
    private boolean isInExclusionRegion(AnnotationSet inputAS, Long currStart, Long currEnd) {
    // Don't process this annotation if it occurs within a defined exclusion zone
        if (excludeIfWithin != null && !(excludeIfWithin.isEmpty())) {
            for (String excludeAnnExpr : excludeIfWithin) {
                String excludeAnnName = getAnnNameFromExpression(excludeAnnExpr);
                AnnotationSet tempAS = inputAS.getCovering(excludeAnnName, currStart, currEnd);
                AnnotationSet excludeAS = getFilteredAS(tempAS, excludeAnnExpr);
                if (!excludeAS.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     *
     * @param ann1End           End point of antecedent annotation
     * @param ann2Start         Start point of potential anaphor annotation
     * @return                  Number of sentences between the two annotations
     */
    private int getSentenceDistance(AnnotationSet inputAS, Long ann1Start, Long ann2End) {
        AnnotationSet spanningSentences = inputAS.get(sentenceName, ann1Start, ann2End);
        return spanningSentences.size() - 1;
    }


    /**
     *
     * @param ann1      antecedent annotation
     * @param ann2      anaphor annotation
     * @return          Number of sentences between the two annotations
     */
    private int getSentenceDistance(AnnotationSet inputAS, Annotation ann1, Annotation ann2) {
        Long ann1Start = ann1.getStartNode().getOffset();
        Long ann2End = ann2.getEndNode().getOffset();
        AnnotationSet spanningSentences = inputAS.get(sentenceName, ann1Start, ann2End);
        return spanningSentences.size() - 1;
    }

    
    /**
     * More rigorous test for definite descriptor based on first or preceding Token string value
     * @param inputAS       input annotation set
     * @param annStart      candidate annotation start offset
     * @param annEnd        candidate annotation end offset
     * @return              true if the annotation is a definite descriptor, i.e. begins with the|this|that|these|those and comprises two words only
     */
    private boolean isDefiniteDescriptor(AnnotationSet inputAS, Long annStart, Long annEnd) {
        List<Annotation> innerToks = new ArrayList<Annotation>(inputAS.getContained(annStart, annEnd).get(tokenName));
        int numWords = getNumWords(innerToks);
        Collections.sort(innerToks, new OffsetComparator());
        if (innerToks.size() > 0 && numWords <= 2) {
            Annotation firstTok = innerToks.get(0);
            String word = (String)firstTok.getFeatures().get(ANNIEConstants.TOKEN_STRING_FEATURE_NAME);
            if (word != null && word.matches(definiteDescriptorRegEx)) {
                return true;
            } else {
                AnnotationSet sentenceAS = inputAS.getCovering(sentenceName, annStart, annEnd);
                if (sentenceAS.isEmpty()) {
                    return false;
                }
                Annotation sentence = sentenceAS.iterator().next();
                Long sentStart = sentence.getStartNode().getOffset();
                Long sentEnd = sentence.getEndNode().getOffset();
                List<Annotation> sentenceToks = new ArrayList<Annotation>(inputAS.getContained(sentStart, sentEnd).get(tokenName));
                Collections.sort(sentenceToks, new OffsetComparator());
                int firstTokPos = sentenceToks.indexOf(firstTok);
                if (firstTokPos > 0) {
                    Annotation prevTok = sentenceToks.get(sentenceToks.indexOf(firstTok) - 1);
                    word = (String)prevTok.getFeatures().get(ANNIEConstants.TOKEN_STRING_FEATURE_NAME);
                    if (word != null && word.matches(definiteDescriptorRegEx)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    /**
     * Quick and dirty method to see if string is a definite descriptor
     * @param str       Some string
     * @return          True of the string begins with the|this|that|these|those and comprises two words only
     */
    private boolean isDefiniteDescriptor(String str) {
        String firstWord = getFirstWord(str);
        int numWords = getNumWords(str);
        if (firstWord.matches(definiteDescriptorRegEx) && numWords == 2) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Tokenization independent method that returns the last word of a string, ignoring any leading or trailing punctuation
     * @param str        input String
     * @return           the last word of the string
     */
    private String getLastWord(String str) {
        String termArr[] = str.split(wordBreakRegEx);
        int termArrLen = termArr.length;
        if (termArrLen == 0) {return "" ; }
        String lastWord = termArr[termArrLen - 1].toLowerCase().trim();
        return lastWord;
    }


    /**
     * Tokenization independent method that returns the first word of a string, ignoring any leading or trailing punctuation
     * @param str        input String
     * @return           the first word of the string
     */
    private String getFirstWord(String str) {
        String termArr[] = str.split(wordBreakRegEx);
        int termArrLen = termArr.length;
        if (termArrLen == 0) {return "" ; }
        String firstWord = termArr[0].toLowerCase().trim();
        return firstWord;
    }

    /**
     *
     * @param str       input String
     * @return          number of words in string by regex
     */
    private int getNumWords(String str) {
        String termArr[] = str.split(wordBreakRegEx);
        return termArr.length;
    }

    
    /**
     * Returns the number of Token.kind == word in the Annotation Set
     * @param tokenAS           AnnotationSet of tokens
     * @return                  Number of Tokens where kind=word
     */
    private int getNumWords(Collection<Annotation> tokenAS) {
        int numWords = 0;
        for (Annotation tok : tokenAS) {
            String kind = (String)tok.getFeatures().get(ANNIEConstants.TOKEN_KIND_FEATURE_NAME);
            if (kind !=null && kind.equals("word")) {
                numWords++;
            }
        }
        return numWords;
    }

    
    /**
     * Matches intersections between 2 paired lists of feature values, i.e. are all items the same,
     * or for complex list items (such as List or Set), is there an intersection
     * @param l1
     * @param l2
     * @return
     */
    private int matchFeatureListIntersection(List antecedentList, List anaphorList) {
        int antecedentListSize = antecedentList.size();
        int anaphorListSize = anaphorList.size();
        int matchingValues = 0;
        
        // Lists must be of the same length
        if (antecedentListSize != anaphorListSize) {
            return 0;
        }
        // And we assume the lists are paired, i.e. items of the same type are in the same position in each list
        for (int i = 0; i < antecedentListSize; i++) {
            Object antecedentFeat = antecedentList.get(i);
            Object anaphorFeat = anaphorList.get(i);

            if (antecedentFeat == null && anaphorFeat == null) { // match on both null counts as a match
                matchingValues++;
            } else if (antecedentFeat.getClass().equals(anaphorFeat.getClass())) {
                // check the feature values are of the same class so we can compare them
                if (antecedentFeat instanceof String) {
                    if ( ((String)antecedentFeat).equalsIgnoreCase((String)anaphorFeat) ) {
                        matchingValues++;
                    }
                } else if(antecedentFeat instanceof List || antecedentFeat instanceof Set) {
                    Set s1 = new HashSet(); Set s2 = new HashSet();
                    s1 = new HashSet((Collection)antecedentFeat);
                    s2 = new HashSet((Collection)anaphorFeat);
                    s1.retainAll(s2);
                    if (! s1.isEmpty() ) {
                        matchingValues++;
                    }
                } else {
                    if ( antecedentFeat.equals(anaphorFeat) ) {
                        matchingValues++;
                    }
                }
            }
        }
        return matchingValues;
    }


    /**
     *
     * @param fm            A FeatureMap
     * @param keyList       List of String keys into FeatureMap
     * @param value         A value we are looking for in FeatureMap with one of the given keys
     * @return              True if value found in FeatureMap for one of keyList
     */
    private boolean matchValueInFeatureMap(FeatureMap fm, List<String> keyList, Object value) {
    	boolean hasMatch = false;
        if (keyList != null) {
            for (String key : keyList) {
                Object feat = fm.get(key);
                if (feat instanceof List || feat instanceof Set) {
                	hasMatch = ((Collection)feat).contains(value);
                } else if (feat instanceof String && value instanceof String) {
                	hasMatch = ((String)feat).equalsIgnoreCase((String)value)   ;
                } else if (feat != null && value != null) {
                    hasMatch = feat.equals(value) ;
                }
                if (hasMatch) { return true; }
            }
        }
        return false;
    }

   
    
    /**
     * Populate a new list with the values of feature keys specified in keyList
     * @param fm            FeatureMap
     * @param newList       New list to be populated
     * @param keyList    List of feature keys for which we want values (including null)
     */
    private void populateFeatureList(FeatureMap fm, List newList, List<String> keyList) {
        if (keyList != null) {
           for (String str : keyList) {
               Object feat = fm.get(str);
               newList.add(feat);   
               // null is OK, shows that feature is missing and will ensure that
               // lists for both antecedent and anaphor are the same size as both use the same keyList
           }
        }
    }
    

    @Override
    public Resource init() throws ResourceInstantiationException {
        gracefulExit = false;

        // Default types are Person, Organization, Location
        inputASTypes = new ArrayList<String>();
        inputASTypes.add(ANNIEConstants.PERSON_ANNOTATION_TYPE);
        inputASTypes.add(ANNIEConstants.ORGANIZATION_ANNOTATION_TYPE);
        inputASTypes.add(ANNIEConstants.LOCATION_ANNOTATION_TYPE);
        
        annFeatsToSortal = new ArrayList<String>();
        annFeatsToSortal.add("type");

        featsClone = new ArrayList<String>();
        featsClone.add(backrefTextFeature);
        return this;
    } // end init()


    @Override
    public void execute() throws ExecutionException {
        interrupted = false;
        // quit if setup failed
        if (gracefulExit) {
            gate.util.Err.println("Plugin was not initialised correctly. Exiting gracefully ... ");
            cleanup();
            fireProcessFinished();
            return;
        }

        // lookup the whole term first, if no results, then lookup individual tokens within the word
        AnnotationSet inputAS = (inputASName == null || inputASName.trim().length() == 0) ? document.getAnnotations() : document.getAnnotations(inputASName);
        AnnotationSet outputAS = (outputASName == null || outputASName.trim().length() == 0) ? document.getAnnotations() : document.getAnnotations(outputASName);

        // Get all Tokens that are words
        FeatureMap tokFeats = Factory.newFeatureMap();

        // Create a string distance metric
        StringDistance[] metrics = null;

        if (similarityMeasure1 != SimilarityMeasure.None) {
            String metric = similarityMeasure1.toString();
            if (similarityMeasure2 != SimilarityMeasure.None && similarityMeasure2 != similarityMeasure1) {
                metric = metric + "/" + similarityMeasure2.toString();
            }
            try {
                metrics = DistanceLearnerFactory.buildArray(metric);
            } catch (IllegalStateException ce) {
                gate.util.Err.println("Unable to create string metric from " + similarityMeasure1.toString());
                cleanup();
                fireProcessFinished();
                gracefulExit = true;
                return;
            }
        }
        
        double threshold = stringMatchThreshold.doubleValue();

        // Document content
        String docContent = document.getContent().toString();

        // Create a List of Lists so that we compare input annots of the same type in separate lists
        List<List<Annotation>> inputAnnsList = new ArrayList<List<Annotation>>();

        // We allow annType of the form
        // Annotation.feature == value or just Annotation. That way, we can have Mention.type == Foo or just Foo
        for (String annType : inputASTypes) {
            AnnotationSet mentionAS = getFilteredAS(inputAS, annType);    // AS to hold the mentions we want to compare
            inputAnnsList.add(new ArrayList<Annotation>(mentionAS));
        }

        for (List<Annotation> inputAnns : inputAnnsList) {
            Collections.sort(inputAnns, new OffsetComparator());
            // Document may not contain any of the inputAnns we are interested in
            if (inputAnns == null || inputAnns.isEmpty()) {
                continue;
            }

            Annotation curr = inputAnns.iterator().next();
            // Shouldn't happen but if document has been modified, it can occur
            if (curr == null) {
                continue;
            }
            boolean matchedPair = false;
            // main body for upper iterator
            while (!inputAnns.isEmpty()) {
                int currId = curr.getId();
                int currIndex = inputAnns.indexOf(curr);
                Long currStart = curr.getStartNode().getOffset();
                Long currEnd = curr.getEndNode().getOffset();
                String currType = curr.getType();

                // Progress bar
                fireProgressChanged(100 * currId / inputAnns.size());
                if (isInterrupted() ) {
                    throw new ExecutionException("Execution of coreference was interrupted.");
                }
                
                inputAnns.remove(curr);     // remove current iteration from the list so we don't check it again

                // Don't process this antecedent if it occurs within a defined exclusion zone
                if (isInExclusionRegion(inputAS, currStart, currEnd)) {
                    continue;
                }

                FeatureMap p1Feats = curr.getFeatures();
                
                // get essential features that must match between p1 and p2
                List p1matchingFeats = new ArrayList();
                populateFeatureList(p1Feats, p1matchingFeats, matchingFeats);
                
                // get general features that should match between p1 and p2
                List p1ComparisonFeatures = new ArrayList();
                populateFeatureList(p1Feats, p1ComparisonFeatures, comparisonFeats);

                // String content of antecedent
                String p1String = "";
                if (contentFeature != null && !contentFeature.isEmpty()) {
                    Object feat = p1Feats.get(contentFeature);
                    if (feat != null ) {
                        p1String = feat.toString().trim();
                    }
                } 
                if ( p1String.isEmpty() ) {
                    p1String = docContent.substring(currStart.intValue(), currEnd.intValue()).trim();
                }

                Integer p1CorefId = (Integer) p1Feats.get(corefIdFeature);

                // Get the last word of the string, we'll add this to the bpoc to see if we have a match
                String p1LastWord = getLastWord(p1String);

                // main body for lower iterator
                for (Iterator<Annotation> itr = inputAnns.iterator(); itr.hasNext();) {
                    Annotation ann = itr.next();
                    if (matchedPair) {
                        for (int i = 0; i < currIndex; i++) {
                            if (itr.hasNext()) {
                                ann = itr.next();
                            }
                        }
                    }
                    matchedPair = false;
                    Long annStart = ann.getStartNode().getOffset();
                    Long annEnd = ann.getEndNode().getOffset();

                    // Sanity check - don't look backwards! Shouldn't happen but the algorithm isn't perfect
                    // when the last match was the final node
                    if (annStart <= currStart || annEnd <= currEnd ) {
                        continue;
                    }
                    
                    boolean isDefiniteDescriptor = isDefiniteDescriptor(inputAS, annStart, annEnd);
                    int sentenceDistance = getSentenceDistance(inputAS, currStart, annEnd);

                    // Don't process this anaphor if it occurs within a defined exclusion zone
                    // or if it is outside the maxNominalSentenceDistance or anaphor is sortal and is outside maxSortalSentenceDistance
                    if (isInExclusionRegion(inputAS, annStart, annEnd) || 
                       (maxNominalSentenceDistance > -1 && maxNominalSentenceDistance < sentenceDistance) ||
                       (isDefiniteDescriptor && maxSortalSentenceDistance > -1 && maxSortalSentenceDistance < sentenceDistance)
                       ) {
                        continue;
                    }

                    FeatureMap p2Feats = ann.getFeatures();
                    // get essential features that must match between p1 and p2
                    List p2matchingFeats = new ArrayList();
                    populateFeatureList(p2Feats, p2matchingFeats, matchingFeats);
                    
                    // get general features that should match between p1 and p2
                    List p2ComparisonFeatures = new ArrayList();
                    populateFeatureList(p2Feats, p2ComparisonFeatures, comparisonFeats);

                    String p2String = "";
                    if (contentFeature != null && !contentFeature.isEmpty()) {
                        Object feat = p2Feats.get(contentFeature);
                        if (feat != null ) {
                            p2String = feat.toString().trim();
                        }
                    }
                    if ( p2String.isEmpty() ) {
                        p2String = docContent.substring(annStart.intValue(), annEnd.intValue()).trim();
                    }

                    Integer p2BackRefId = (Integer) p2Feats.get(backrefIdFeature);		// get link back to existing coreferring Mention - if it's there, we don't want to link again

                    // Get the last word of the string, we'll add this to the bpoc to see if we have a match
                    String p2LastWord = getLastWord(p2String);

                    // For simple lists of primitives, we could just do p1matchingFeats.equals(p2matchingFeats), but we might have more complex list item types
                    int numEssentialFeatureMatches = matchFeatureListIntersection(p1matchingFeats, p2matchingFeats);
                    int numFeatureMatches = matchFeatureListIntersection(p1ComparisonFeatures, p2ComparisonFeatures);
                    int nummatchingFeats = p1matchingFeats.size();
                    int numComparisonFeatures = p1ComparisonFeatures.size();

                    // fraction of comparison features that match over all comparison features
                    double featureMatchRatio = (numComparisonFeatures == 0) ? 1.0 : ((double)numFeatureMatches) / numComparisonFeatures;
                    double compareScore = 0.0;
                    // Check that essential features match and that p1 & p2 aren't already coreferenced nor do they appear in an exclude zone
                    if (numEssentialFeatureMatches == nummatchingFeats &&
                            featureMatchRatio >= featureMatchThreshold && 
                            p1CorefId == null && p2BackRefId == null ) {
                       
                        // First test - do strings match exactly
                        if (!matchedPair && p1String.length() >= shortestWord && p2String.length() >= shortestWord && p1String.equalsIgnoreCase(p2String)) {
                            matchedPair = true;
                            compareScore = 1.0;
                        } else {
                            // Filter through the sieve
                            if (!matchedPair && isDefiniteDescriptor) {
                                // definite descriptor anaphors headword can be matched against antecedent headword, e.g. 'left basilar atelectasis' with 'the atelectasis'
                                if (p1LastWord.equalsIgnoreCase(p2LastWord)) {
                                    matchedPair = true;
                                    compareScore = 0.8;
                                }
                                // Check for sortal anaphor match against antecedent type or feature value
                                else if(annTypeToSortal) {         // e.g. '{Location}garden square' to '{Location}that location'
                                    if (currType.equalsIgnoreCase(p2LastWord) ) {
                                        matchedPair = true;
                                        compareScore = 0.75;
                                    }
                                }
                                if (!matchedPair) { // e.g. type=city and 'the city'
                                    if (matchValueInFeatureMap(p1Feats, annFeatsToSortal, p2LastWord)) {
                                        matchedPair = true;
                                        compareScore = 0.75;
                                    } else {    // we've got feature matches but nothing else, but as this is a sortal reference and close to the antecedent, raise a tentative match
                                        if (numComparisonFeatures > 0 || nummatchingFeats > 0) {
                                            matchedPair = true;
                                            compareScore = 0.5;
                                        }
                                    }
                                }
                            }
                            if (!matchedPair) {
                                if (matchValueInFeatureMap(p1Feats, annFeatsToContent, p2LastWord) ||
                                        matchValueInFeatureMap(p2Feats, annFeatsToContent, p1LastWord)
                                        ) {
                                    matchedPair = true;
                                    // System.out.println("matched " + p2LastWord + " with " + p2LastWord);
                                    compareScore = 0.65;
                                }
                            }
                            // Headword only match - can be risky
                            /*
                            if (!matchedPair && p1LastWord.equalsIgnoreCase(p2LastWord)) {
                                matchedPair = true;
                                compareScore = 0.5;
                            }
                            */
                            // Approximate string match
                            if (!matchedPair && p1String.length() >= shortestWord && p2String.length() >= shortestWord) {
                                // Calculate string similarity if string lengths are longer than shortestWord
                                StringWrapper sw1 = metrics[0].prepare(p1String);
                                StringWrapper sw2 = metrics[0].prepare(p2String);
                                double metric1Score = metrics[0].score(sw1, sw2);
                                double metric2Score = 0.0;
                                compareScore = metric1Score;
                                if (metrics.length == 2) {
                                    metric2Score = metrics[1].score(sw1, sw2);
                                    if (similarityComparison == MeasureCompare.mean) {
                                        compareScore = (metric1Score + metric2Score) / 2;
                                    } else if (similarityComparison == MeasureCompare.max) {
                                        compareScore = Math.max(metric1Score, metric2Score);
                                    } else {
                                        compareScore = Math.min(metric1Score, metric2Score);
                                    }
                                } else {
                                    compareScore = metric1Score;
                                }
                                if (compareScore >= threshold) {
                                    matchedPair = true;
                                }
                            } // end if
                        } // end if
                    } // end if


                    if (matchedPair) {
                        // mark the coref
                        p1Feats.put("score", compareScore);
                        p1Feats.put(corefIdFeature, ann.getId());
                        p1Feats.put(corefTextFeature, p2String);		// coreferent text
                        // mark the backref
                        p2Feats.put(backrefIdFeature, currId);
                        p2Feats.put(backrefTextFeature, p1String);

                        // Propagate the featsClone from antecedent to the anaphor
                        if (featsClone != null) {
                            for (String feat : featsClone) {
                                Object featVal = p1Feats.get(feat);
                                if (featVal != null) {
                                    p2Feats.put(feat, featVal);
                                }
                            }
                        }
                        // itr.remove();
                        curr = ann;
                        break;
                    } // end if matchedPair
                } // end for loop over lower iterator
                if (inputAnns.iterator().hasNext()) {
                    Annotation next = inputAnns.iterator().next();
                    if (!matchedPair) {
                        curr = next;
                    }
                }
                // end main body
            } // end while over upper iterator
        } // end for

        fireProcessFinished();
    } // end execute()

    
    @Optional
    @RunTime
    @CreoleParameter(defaultValue = "backRefText", comment = "Feature that holds the string content of each of inputASTypes, or use annotation content if null or not specified")
    public void setContentFeature(String contentFeature) {
        this.contentFeature = contentFeature;
    }

    public String getContentFeature() {
        return contentFeature;
    }

    
    @Optional
    @RunTime
    @CreoleParameter(comment = "If set, compare for similarity the content of the given features from annotations within inputASTypes")
    public void setComparisonFeats(ArrayList<String> comparisonFeats) {
        this.comparisonFeats = comparisonFeats;
    }

    public ArrayList<String> getComparisonFeats() {
        return comparisonFeats;
    }

 

    @Optional
    @RunTime
    @CreoleParameter(comment = "If set, these features must be present in both mentions and must match")
    public void setMatchingFeats(ArrayList<String> matchingFeats) {
        this.matchingFeats = matchingFeats;
    }

    public ArrayList<String> getMatchingFeats() {
        return matchingFeats;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Input Annotation Set Name")
    public void setInputASName(String inputASName) {
        this.inputASName = inputASName;
    }

    public String getInputASName() {
        return inputASName;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Output Annotation Set Name")
    public void setOutputASName(String outputASName) {
        this.outputASName = outputASName;
    }

    public String getOutputASName() {
        return outputASName;
    }


    @RunTime
    @CreoleParameter(comment = "Compare for similarity the content of the given Annotations in the input Annotation Set")
    public void setInputASTypes(ArrayList<String> inputASTypes) {
        this.inputASTypes = inputASTypes;
    }

    public ArrayList<String> getInputASTypes() {
        return inputASTypes;
    }


    @Optional
    @RunTime
    @CreoleParameter(comment = "Don't attempt to coreference terms that are within these annotations")
    public void setExcludeIfWithin(ArrayList<String> excludeIfWithin) {
        this.excludeIfWithin = excludeIfWithin;
    }

    public ArrayList<String> getExcludeIfWithin() {
        return excludeIfWithin;
    }

    @RunTime
    @CreoleParameter(defaultValue = "true",
    comment = "Form bridging coref from annotation name to string content of sortal anaphors?")
    public void setAnnTypeToSortal(Boolean annTypeToSortal) {
        this.annTypeToSortal = annTypeToSortal;
    }

    public Boolean getAnnTypeToSortal() {
        return annTypeToSortal;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Form bridging coref from values of these features to string content of sortal anaphors?, e.g. 'type=city' and 'this city'")
    public void setAnnFeatsToSortal(ArrayList<String> annFeatsToSortal) {
        this.annFeatsToSortal = annFeatsToSortal;
    }

    public ArrayList<String> getAnnFeatsToSortal() {
        return annFeatsToSortal;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "Antecedent features that can match anaphor string")
    public void setAnnFeatsToContent(ArrayList<String> annFeatsToContent) {
        this.annFeatsToContent = annFeatsToContent;
    }

    public ArrayList<String> getAnnFeatsToContent() {
        return annFeatsToContent;
    }

    @Optional
    @RunTime
    @CreoleParameter(comment = "List of features that should be cloned from antecedent to anaphor")
    public void setFeatsClone(ArrayList<String> featsClone) {
        this.featsClone = featsClone;
    }

    public ArrayList<String> getFeatsClone() {
        return featsClone;
    }

    @RunTime
    @CreoleParameter(defaultValue="backRefId", comment="Feature name for back reference id")
    public void setBackrefIdFeature(String backrefIdFeature) {
        this.backrefIdFeature = backrefIdFeature;
    }

    public String getBackrefIdFeature() {
        return backrefIdFeature;
    }

    @RunTime
    @CreoleParameter(defaultValue="backRefText", comment="Feature name for antecedent back reference text")
    public void setBackrefTextFeature(String backrefTextFeature) {
        this.backrefTextFeature = backrefTextFeature;
    }

    public String getBackrefTextFeature() {
        return backrefTextFeature;
    }

    @RunTime
    @CreoleParameter(defaultValue="corefId", comment="Feature name for coreference id")
    public void setCorefIdFeature(String corefIdFeature) {
        this.corefIdFeature = corefIdFeature;
    }

    public String getCorefIdFeature() {
        return corefIdFeature;
    }


    @RunTime
    @CreoleParameter(defaultValue="corefText", comment="Feature name for coreferring text")
    public void setCorefTextFeature(String corefTextFeature) {
        this.corefTextFeature = corefTextFeature;
    }

    public String getCorefTextFeature() {
        return corefTextFeature;
    }

    @RunTime
    @CreoleParameter(defaultValue = "4",
    comment = "Minimum word length to trigger a string comparison check")
    public void setShortestWord(Integer shortestWord) {
        this.shortestWord = shortestWord;
    }

    public Integer getShortestWord() {
        return shortestWord;
    }

    @RunTime
    @CreoleParameter(defaultValue = "JaroWinkler",
    comment = "Select a similarity measure to compare strings")
    public void setSimilarityMeasure1(SimilarityMeasure similarityMeasure) {
        this.similarityMeasure1 = similarityMeasure;
    }

    public SimilarityMeasure getSimilarityMeasure1() {
        return similarityMeasure1;
    }

    @RunTime
    @CreoleParameter(defaultValue = "MongeElkan",
    comment = "Select a similarity measure to compare strings")
    public void setSimilarityMeasure2(SimilarityMeasure similarityMeasure) {
        this.similarityMeasure2 = similarityMeasure;
    }

    public SimilarityMeasure getSimilarityMeasure2() {
        return similarityMeasure2;
    }

    @RunTime
    @CreoleParameter(defaultValue = "max",
    comment = "Joint metric comparison type")
    public void setSimilarityComparison(MeasureCompare similarityComparison) {
        this.similarityComparison = similarityComparison;
    }

    public MeasureCompare getSimilarityComparison() {
        return similarityComparison;
    }

    @RunTime
    @CreoleParameter(defaultValue = "0.90",
    comment = "String similarity threshold score")
    public void setStringMatchThreshold(Double stringMatchThreshold) {
        this.stringMatchThreshold = stringMatchThreshold;
    }

    public Double getStringMatchThreshold() {
        return stringMatchThreshold;
    }

    
    @RunTime
    @CreoleParameter(defaultValue = "0.80",
    comment = "Fraction of comparisonFeats that must match between antecedent and anaphor")
    public void setFeatureMatchThreshold(Double featureMatchThreshold) {
        this.featureMatchThreshold = featureMatchThreshold;
    }

    
    public Double getFeatureMatchThreshold() {
        return featureMatchThreshold;
    }

    
    @RunTime
    @CreoleParameter(defaultValue = ANNIEConstants.SENTENCE_ANNOTATION_TYPE,
    comment = "Sentence annotation name")
    public void setSentenceName(String sentenceName) {
        this.sentenceName = sentenceName;
    }

    public String getSentenceName() {
        return sentenceName;
    }

    @RunTime
    @CreoleParameter(defaultValue="10", comment="Maximum sentence distance between antecedent and nominal anaphor")
    public void setMaxNominalSentenceDistance(Integer maxNominalSentenceDistance) {
        this.maxNominalSentenceDistance = maxNominalSentenceDistance;
    }

    public Integer getMaxNominalSentenceDistance() {
        return maxNominalSentenceDistance;
    }


    @RunTime
    @CreoleParameter(defaultValue="1", comment="Maximum sentence distance between antecedent and sortal anaphor")
    public void setMaxSortalSentenceDistance(Integer maxSortalSentenceDistance) {
        this.maxSortalSentenceDistance = maxSortalSentenceDistance;
    }

    public Integer getMaxSortalSentenceDistance() {
        return maxSortalSentenceDistance;
    }

    @RunTime
    @CreoleParameter(defaultValue = ANNIEConstants.TOKEN_ANNOTATION_TYPE,
    comment = "Name of Token annotation")
    public void setTokenName(String tokenName) {
        this.tokenName = tokenName;
    }

    public String getTokenName() {
        return tokenName;
    }
}
