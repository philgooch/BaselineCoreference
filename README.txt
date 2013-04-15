The Baseline Coreference PR provides a flexible framework for coreference of previously annotated texts. The PR creates in-document coreference chains between successive antecedent-anaphor pairs.

For best results, use in conjunction with the Pronoun Annotator PR and use both after the Morphological Analyser in your pipeline.

For example, a typical pipeline might comprise:

All the ANNIE PRs
GATE Morphological Analyser
WordNet Suggester [to add hypernym, meronym and synonym information]
Pronoun Annotator
Baseline Coreference

The Baseline Coreference PR provides coreference only. Named entities, including definite descriptors (such as 'the city', 'that location', 'this disease') need to have been previously annotated (e.g. by ANNIE, a modified version thereof, or Pronoun Annotator).


Run-time parameters
===================

annFeatsToSortal:	Optional list of features whose values will be used for bridging, sortal coreference. E.g. 'type' for 'type=city' to match against a sortal mention 'that city'.

annFeatsToContent:	Optional list of antecedent and/or anaphor features whose values will be tested for a match against the antecedent or anaphor string. E.g. synonyms, hypernyms, meronyms

annTypeToSortal:	Match annotation name to the string content of sortal anaphor mentions. E.g. match a Location annotation to 'this location'. Defaults to true.
	

backrefIdFeature:	Feature that will store a back-link from the anaphor to the antecedent. Defaults to 'backRefId'.

backrefTextFeature:	Feature that will store the text of the antecedent on the anaphor. Defaults to 'backRefText'.

cloneFeatures:		Optional list of features that should be copied from antecedent to anaphor along the coreference chain. Defaults to the value of 'backrefTextFeature' (i.e. copy the text of head of the coreference chain across all anaphors).

comparisonFeatures:	Optional list of features that should be compared between a candidate antecedent-anaphor pair. The number of features in this list that should match is determined by featureMatchThreshold (see below).

contentFeature:		Feature that should be used as an alias for the antecedent string content. E.g. this might contain a normalized string.

corefIdFeature:		Feature that will store the link from antecedent to anaphor. Defaults to 'corefId'.

corefTextFeature:	Feature that will store the text of the anaphor on the antecedent. Defaults to 'corefText'.

matchingFeats:	Optional list of features that *must* match between a candidate antecedent-anaphor pair for a pairing to be considered.

excludeIfWithin:	Do not consider antecedent-anaphor pair if either occurs within one of these listed annotation types.

featureMatchThreshold:	Fraction of comparisonFeatures that should match between a candidate antecedent-anaphor pair. Defaults to 0.8.


inputASName:		Input Annotation Set name.

inputASTypes:		List of annotation types to be considered. Defaults to Person, Organization, Location.

maxNominalSentenceDistance:	Maximum number of sentences between a candidate antecedent-anaphor pair for nominal coreference. Defaults to 10.

maxSortalSentenceDistance:	Maximum number of sentences between a candidate antecedent-anaphor pair for sortal/definite descriptor bridging coreference. Defaults to 1.

outputASName:		Output Annotation Set name.

sentenceName:		Name of Sentence annotations. Defaults to Sentence (normally you would not change this).

shortestWord:		Shortest word for string similarity comparison. Defaults to 4.

similarityComparison:	When using two string similarity metrics, choose the max, min, or mean of the two values. Defaults to 'max'.

similarityMeasure1:	Similarity metric to use for string comparison. Defaults to Jaro-Winkler.

similarityMeasure2:	Auxiliary metric to use for string comparison. Defaults to Monge-Elkan.

stringMatchThreshold:	Minimum similarity measure score to trigger a nominal coreference match. Defaults to 0.9.

tokenName:		Name of Token annotations. Defaults to Token (normally you would not change this).
