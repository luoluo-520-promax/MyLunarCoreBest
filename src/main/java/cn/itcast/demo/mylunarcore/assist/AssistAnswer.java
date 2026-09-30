package cn.itcast.demo.mylunarcore.assist;



import java.util.List;



/**

 * 助手对外返回的统一答复对象。

 */

public record AssistAnswer(

        int retcode,

        String answer,

        String source,

        List<CoachHint> relatedHints,

        List<String> citedConfigIds,

        String disclaimer,

        String strategyVersion,

        String locale,

        List<AssistMediaLink> mediaLinks,

        String inlineSummary,

        boolean forbidExternalBrowser,

        SuggestedAutoOverride suggestedAutoOverride,

        String personaId,

        int inputType,

        int estimatedWaitMs,

        String renderType,

        String renderPayloadJson

) {

    public static AssistAnswer of(int retcode, String answer, String source,

                                  List<CoachHint> relatedHints, List<String> citedConfigIds) {

        return of(retcode, answer, source, relatedHints, citedConfigIds, "", "", "", List.of(), "", true,

                null, "", 0, 0, "", "");

    }



    public static AssistAnswer of(int retcode, String answer, String source,

                                  List<CoachHint> relatedHints, List<String> citedConfigIds,

                                  String disclaimer, String strategyVersion, String locale) {

        return of(retcode, answer, source, relatedHints, citedConfigIds, disclaimer, strategyVersion, locale,

                List.of(), "", true, null, "", 0, 0, "", "");

    }



    public static AssistAnswer of(int retcode, String answer, String source,

                                  List<CoachHint> relatedHints, List<String> citedConfigIds,

                                  String disclaimer, String strategyVersion, String locale,

                                  List<AssistMediaLink> mediaLinks) {

        return of(retcode, answer, source, relatedHints, citedConfigIds, disclaimer, strategyVersion, locale,

                mediaLinks, "", true, null, "", 0, 0, "", "");

    }



    public static AssistAnswer of(int retcode, String answer, String source,

                                  List<CoachHint> relatedHints, List<String> citedConfigIds,

                                  String disclaimer, String strategyVersion, String locale,

                                  List<AssistMediaLink> mediaLinks,

                                  String inlineSummary,

                                  boolean forbidExternalBrowser) {

        return of(retcode, answer, source, relatedHints, citedConfigIds, disclaimer, strategyVersion, locale,

                mediaLinks, inlineSummary, forbidExternalBrowser, null, "", 0, 0, "", "");

    }



    public static AssistAnswer of(int retcode, String answer, String source,

                                  List<CoachHint> relatedHints, List<String> citedConfigIds,

                                  String disclaimer, String strategyVersion, String locale,

                                  List<AssistMediaLink> mediaLinks,

                                  String inlineSummary,

                                  boolean forbidExternalBrowser,

                                  SuggestedAutoOverride suggestedAutoOverride,

                                  String personaId,

                                  int inputType) {

        return of(retcode, answer, source, relatedHints, citedConfigIds, disclaimer, strategyVersion, locale,

                mediaLinks, inlineSummary, forbidExternalBrowser, suggestedAutoOverride, personaId, inputType,

                0, "", "");

    }



    public static AssistAnswer of(int retcode, String answer, String source,

                                  List<CoachHint> relatedHints, List<String> citedConfigIds,

                                  String disclaimer, String strategyVersion, String locale,

                                  List<AssistMediaLink> mediaLinks,

                                  String inlineSummary,

                                  boolean forbidExternalBrowser,

                                  SuggestedAutoOverride suggestedAutoOverride,

                                  String personaId,

                                  int inputType,

                                  int estimatedWaitMs,

                                  String renderType,

                                  String renderPayloadJson) {

        return new AssistAnswer(

                retcode,

                answer == null ? "" : answer,

                source == null ? "" : source,

                relatedHints == null ? List.of() : List.copyOf(relatedHints),

                citedConfigIds == null ? List.of() : List.copyOf(citedConfigIds),

                disclaimer == null ? "" : disclaimer,

                strategyVersion == null ? "" : strategyVersion,

                locale == null ? "" : locale,

                mediaLinks == null ? List.of() : List.copyOf(mediaLinks),

                inlineSummary == null ? "" : inlineSummary,

                forbidExternalBrowser,

                suggestedAutoOverride,

                personaId == null ? "" : personaId,

                inputType,

                estimatedWaitMs,

                renderType == null ? "" : renderType,

                renderPayloadJson == null ? "" : renderPayloadJson);

    }



    public AssistAnswer withMeta(String disclaimer, String strategyVersion, String locale) {

        return of(retcode, answer, source, relatedHints, citedConfigIds, disclaimer, strategyVersion, locale,

                mediaLinks, inlineSummary, forbidExternalBrowser, suggestedAutoOverride, personaId, inputType,

                estimatedWaitMs, renderType, renderPayloadJson);

    }



    public AssistAnswer withMediaLinks(List<AssistMediaLink> links) {

        return of(retcode, answer, source, relatedHints, citedConfigIds, disclaimer, strategyVersion, locale,

                links, inlineSummary, forbidExternalBrowser, suggestedAutoOverride, personaId, inputType,

                estimatedWaitMs, renderType, renderPayloadJson);

    }



    public AssistAnswer withAnswer(String newAnswer) {

        return of(retcode, newAnswer, source, relatedHints, citedConfigIds, disclaimer, strategyVersion, locale,

                mediaLinks, inlineSummary, forbidExternalBrowser, suggestedAutoOverride, personaId, inputType,

                estimatedWaitMs, renderType, renderPayloadJson);

    }



    public AssistAnswer withInlineSummary(String summary) {

        return of(retcode, answer, source, relatedHints, citedConfigIds, disclaimer, strategyVersion, locale,

                mediaLinks, summary, forbidExternalBrowser, suggestedAutoOverride, personaId, inputType,

                estimatedWaitMs, renderType, renderPayloadJson);

    }



    public AssistAnswer withSuggestedAutoOverride(SuggestedAutoOverride override) {

        return of(retcode, answer, source, relatedHints, citedConfigIds, disclaimer, strategyVersion, locale,

                mediaLinks, inlineSummary, forbidExternalBrowser, override, personaId, inputType,

                estimatedWaitMs, renderType, renderPayloadJson);

    }



    public AssistAnswer withPersona(String personaId) {

        return of(retcode, answer, source, relatedHints, citedConfigIds, disclaimer, strategyVersion, locale,

                mediaLinks, inlineSummary, forbidExternalBrowser, suggestedAutoOverride, personaId, inputType,

                estimatedWaitMs, renderType, renderPayloadJson);

    }



    public AssistAnswer withInputType(int inputType) {

        return of(retcode, answer, source, relatedHints, citedConfigIds, disclaimer, strategyVersion, locale,

                mediaLinks, inlineSummary, forbidExternalBrowser, suggestedAutoOverride, personaId, inputType,

                estimatedWaitMs, renderType, renderPayloadJson);

    }



    public AssistAnswer withEstimatedWait(int waitMs) {

        return of(retcode, answer, source, relatedHints, citedConfigIds, disclaimer, strategyVersion, locale,

                mediaLinks, inlineSummary, forbidExternalBrowser, suggestedAutoOverride, personaId, inputType,

                waitMs, renderType, renderPayloadJson);

    }



    public AssistAnswer withRender(String type, String payloadJson) {

        return of(retcode, answer, source, relatedHints, citedConfigIds, disclaimer, strategyVersion, locale,

                mediaLinks, inlineSummary, forbidExternalBrowser, suggestedAutoOverride, personaId, inputType,

                estimatedWaitMs, type, payloadJson);

    }

}


