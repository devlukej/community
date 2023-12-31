package com.example.Community.Controller;

import com.example.Community.domain.entity.TestHistory;
import com.example.Community.domain.entity.TestResult;
import com.example.Community.domain.entity.Test;
import com.example.Community.domain.repository.TestRepository;
import com.example.Community.dto.TestResultDto;
import com.example.Community.service.*;
import com.example.Community.dto.TestDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Controller
public class TestController {

    private final TestServiceImpl testService;
    private final TestRepository testRepository;
    private final TestResultService testResultService;

    private final TestHistoryService testHistoryService;

    private final UserService userService;

    @Autowired
    public TestController(TestServiceImpl testService, TestRepository testRepository, TestResultService testResultService, TestHistoryService testHistoryService, UserService userService) {
        this.testService = testService;
        this.testRepository = testRepository;
        this.testResultService = testResultService;
        this.testHistoryService = testHistoryService;
        this.userService = userService;
    }

    @GetMapping("/private/pre-cbt")
    public String categorySelectionPage(@AuthenticationPrincipal MemberUser user, Model model) {

        model.addAttribute("user", user);
        return "board/cbt/pre-cbt"; // 카테고리 선택 HTML 페이지
    }

    // 시험 문제를 표시하는 페이지
    @PostMapping("/private/cbt")
    public String showCbt(@AuthenticationPrincipal MemberUser user,
                          @RequestParam("name") String name,
                          @RequestParam("year") String year,
                          @RequestParam("subject") String subject, Model model) {

        if (user == null) {
            return "redirect:/login";
        }

        List<TestDto> tests = testService.getQuestionsByCategories(name, year, subject);

        model.addAttribute("user", user);
        model.addAttribute("tests", tests);

        return "board/cbt/cbt";
    }

    @Transactional
    @PostMapping("/private/submit-cbt")
    public String submitCbt(@AuthenticationPrincipal MemberUser user, @RequestParam Map<String, String> requestParams, Model model, HttpServletRequest request) {

        if (user == null) {
            return "redirect:/login";
        }

        List<TestResult> testResults = new ArrayList<>();
        int totalScore = 0;

        // 사용자의 선택 항목을 수집하는 로직 추가
        for (String paramName : requestParams.keySet()) {
            if (paramName.startsWith("selectedAnswers[")) {
                String selectedAnswer = requestParams.get(paramName);

                // paramName에서 인덱스를 추출
                int index = Integer.parseInt(paramName.replace("selectedAnswers[", "").replace("]", ""));

                // testId 관련 파라미터를 가져오고 null 체크를 수행
                String testIdParamName = "testId[" + index + "]";
                if (requestParams.containsKey(testIdParamName)) {
                    Long testId = Long.parseLong(requestParams.get(testIdParamName));

                    // Test 엔터티에서 해당 테스트 가져오기
                    Test test = testService.getTestByTestId(testId);

                    if (test != null) {
                        boolean isCorrect = test.isCorrect(selectedAnswer);

                        if (isCorrect) {
                            totalScore += 10; // 정답인 경우 총점 증가
                            userService.increaseUserTier(user.getUserEntity(), 3);
                        }

                        TestResult testResult = new TestResult();
                        testResult.setUser(user.getUserEntity());
                        testResult.setTest(test);
                        testResult.setSelectedAnswer(selectedAnswer);
                        testResult.setCorrect(isCorrect);
                        testResult.setAnswer(test.getAnswer());

                        testResults.add(testResult);

                    } else {
                        // 해당 테스트를 찾을 수 없는 경우 예외 처리 또는 처리할 내용을 추가
                    }
                } else {
                    // testIdParamName이 없는 경우 처리할 내용을 추가
                }
            }
        }

        // testResults에 결과 저장
        testResultService.saveAllTestResults(testResults);

        // TestHistory 엔터티 생성 및 저장
        if (!testResults.isEmpty()) {
            TestHistory testHistory = new TestHistory();
            testHistory.setUserId(Long.parseLong(user.getUserEntity().getId()));
            testHistory.setTotalScore(totalScore);

            testHistoryService.saveTestHistory(testHistory);
            TestHistory savedHistory = testHistoryService.saveTestHistory(testHistory);

            // TestResult 엔터티와 TestHistory 엔터티 연결
            if (savedHistory != null) {
                testResults.forEach(result -> result.setTestHistory(savedHistory));
                testResultService.saveAllTestResults(testResults);
            }
        }

        // HttpSession 객체를 통해 totalScore를 세션에 저장
        HttpSession session = request.getSession();
        session.setAttribute("totalScore", totalScore);

        model.addAttribute("testResults", testResults);

        model.addAttribute("totalScore", totalScore); // totalScore는 총점 변수 이름

        model.addAttribute("user", user);
        return "redirect:/private/cbt-result";
    }


    @GetMapping("/private/cbt-result")
    public String showCbtResultPage(@AuthenticationPrincipal MemberUser user, Model model, HttpServletRequest request) {

        if (user == null) {
            return "redirect:/login";
        }

        // TestResult 엔티티에서 사용자의 결과 조회
        List<TestResultDto> userResults = testResultService.getUserResults(user.getUsername());

        HttpSession session = request.getSession();
        int totalScore = (int) session.getAttribute("totalScore");

        model.addAttribute("user", user);
        model.addAttribute("totalScore", totalScore);
        model.addAttribute("userResults", userResults);

        return "board/cbt/cbt-result"; // 시험 결과를 표시하는 HTML 페이지
    }

    @GetMapping("/private/wrong")
    public String showRandomWrongQuestion(@AuthenticationPrincipal MemberUser user, Model model, HttpSession session, RedirectAttributes redirectAttributes) {

        List<TestResultDto> userWrongResults = null;

        if (user != null) {
            model.addAttribute("user", user);

            userWrongResults = testResultService.getUserWrongResults(user.getUsername());

            if (userWrongResults.isEmpty()) {
                redirectAttributes.addFlashAttribute("noWrongResults", true);

                return "redirect:/private/wrong-empty"; // 또는 사용자가 틀린 문제가 없는 경우를 나타내는 다른 페이지로 이동
            }
        } else {

            redirectAttributes.addFlashAttribute("noWrongResults", true);

            return "redirect:/private/wrong-empty";

        }

        Random random = new Random();
        // 랜덤 문제 선택
        int randomIndex = random.nextInt(userWrongResults.size());
        TestResultDto randomWrongResult = userWrongResults.get(randomIndex);

        session.setAttribute("currentWrongResultId", randomWrongResult.getId());

        model.addAttribute("randomWrongResult", randomWrongResult);

        return "board/wrong/wrong"; // 랜덤 틀린 문제를 표시하는 페이지

    }

    @Transactional
    @PostMapping("/private/submit-wrong-cbt")
    public String submitWrongCbt(@AuthenticationPrincipal MemberUser user,
                                 @RequestParam("testResultId") Long testResultId,
                                 @RequestParam("selectedAnswer") String selectedAnswer,
                                 Model model, HttpSession session) {
        if (user == null) {
            return "redirect:/login";
        }

        // 세션에서 현재 보여지는 틀린 문제의 ID를 가져옵니다
        Long currentWrongResultId = (Long) session.getAttribute("currentWrongResultId");

        // 기존 TestResult 엔티티를 찾습니다.
        TestResult testResult = testResultService.getTestResultById(currentWrongResultId);

        if (testResult != null) {

            Test test = testResult.getTest(); // TestResult에 속한 테스트 가져오기
            boolean isCorrect = test.isCorrect(selectedAnswer);


            // 필드를 변경합니다.
            testResult.setSelectedAnswer(selectedAnswer);
            testResult.setCorrect(isCorrect);

            if (testResult.isCorrect()) {
                userService.increaseUserTier(user.getUserEntity(), 3);
            }

            // 변경된 엔티티를 저장합니다.
            testResultService.saveTestResult(testResult);
        } else {
            // 해당 테스트 결과를 찾을 수 없는 경우 예외 처리 또는 처리할 내용을 추가
        }

        // 오답 노트로 이동
        return "redirect:/private/wrong-result";
    }

    @GetMapping("/private/wrong-result")
    public String showWrongResultPage(@AuthenticationPrincipal MemberUser user, Model model, HttpSession session) {

        if (user == null) {
            return "redirect:/login";
        }

        // 세션에서 현재 보여지는 틀린 문제의 ID를 가져옵니다
        Long currentWrongResultId = (Long) session.getAttribute("currentWrongResultId");

        // currentWrongResultId로부터 실제 TestResult 객체를 가져옵니다.
        TestResult currentTestResult = testResultService.getTestResultById(currentWrongResultId);

        // TestResult 객체가 null이 아닌지 확인
        if (currentTestResult != null) {
            // TestResult 객체로부터 Test 객체를 가져옵니다.
            Test currentTest = currentTestResult.getTest();

            model.addAttribute("user", user);
            model.addAttribute("currentTestResult", currentTestResult);
            model.addAttribute("currentTest", currentTest);

            return "board/wrong/wrong-result"; // wrong-result 페이지로 이동
        }

        return "/";
    }

    @GetMapping("/private/wrong-empty")
    public String showWrongEmpty(@AuthenticationPrincipal MemberUser user, Model model) {

        model.addAttribute("user", user);

        return "board/wrong/wrong-empty";
    }

    @GetMapping("/api/testHistories")
    public List<TestHistory> getTestHistories(@RequestParam String userId) {
        return testHistoryService.getTestHistoryByUserIdAndDate(userId);
    }


}

