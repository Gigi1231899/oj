-- ═══════════════════════════════════════════════════════════════
-- D-OnlineJudge 业务库表结构 + 测试数据（无语法错误版）
-- ═══════════════════════════════════════════════════════════════

-- 1. 建库（如果库存在会直接忽略）
CREATE DATABASE IF NOT EXISTS `doj_problem` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `doj_submission` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `doj_user` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ═══════════════════════════════════════════════
-- 库 1：doj_problem（题库服务）
-- ═══════════════════════════════════════════════
USE `doj_problem`;

DROP TABLE IF EXISTS `problem`;
CREATE TABLE `problem` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL,
  `description` mediumtext NOT NULL,
  `input_style` text NOT NULL,
  `output_style` text NOT NULL,
  `input_sample` json NOT NULL,
  `output_sample` json NOT NULL,
  `difficulty` varchar(10) NOT NULL,
  `time_limit` int NOT NULL,
  `memory_limit` int NOT NULL,
  `hint` text,
  `total_pass` int NOT NULL DEFAULT '0',
  `total_attempt` int NOT NULL DEFAULT '0',
  `test_data` longtext NOT NULL,
  `test_ans` longtext NOT NULL,
  `source_type` varchar(16) NOT NULL DEFAULT 'personal',
  `source_name` varchar(64) DEFAULT NULL,
  `source_link` varchar(512) DEFAULT NULL,
  `solution` mediumtext,
  `checker_config` longtext COMMENT 'Checker  JSON',
  `standard_code` longtext,
  `standard_lang` varchar(16) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_problem_difficulty` (`difficulty`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 完整的 problem 表数据 (ID 1-11)
INSERT INTO `problem` (
    `id`, `name`, `description`, `input_style`, `output_style`, 
    `input_sample`, `output_sample`, `difficulty`, `time_limit`, `memory_limit`, 
    `hint`, `total_pass`, `total_attempt`, `test_data`, `test_ans`, 
    `source_type`, `source_name`, `source_link`, `solution`, 
    `checker_config`, `standard_code`, `standard_lang`
) VALUES 
(1,'无重复字符的最长子串','给定一个字符串 s ，请你找出其中不含有重复字符的 最长 子串 的长度。','字符串s','整数','["abcabcbb", "bbbbb\\n---"]','["3", "1"]','中等',2000,2048,'0 <= s.length <= 105，s 由英文字母、数字、符号和空格组成',3,13,'E4JXFMgA3QUjzT7aqPkyoBVHL0oQnwGrCtW1lit689mVMYMMU8McCQ4VNeQ0Qd4XcRRA4na0TlUGVSVqfqwWqNwGm8p5iqtG5lpY08sloUnrLx4pnpeFhnb47vSiGXx38UUytQhww9HbCkhKIhJoHcykCwBVWRrT4EjzbzqXF1DK56Z7ukb7GWa05j12PpQggmKJeWVUKflZjbEpGUDjWb5qaTowWNTdtEi0wIHDpfSDPFGSZIHSX9Vgo1SwACFvw5bPmohpFxwHpJbYjsgfTecCR621xXotcNSpsdQYr1UVlDJh9wPXMtnhogf6BiWmIIVxeTb3Tjts62cCNxuClthLj1unxXoVuuR0SfnmMEq852kZ2kK1LtxTuEvxR1cmlzKRajPdz57wJaRTOVTWnVj9uhw7NVBvUKlELatSRHQa0OsMgbgbWMrGhmfnLrt4wTmRLYDCq3ru0d1bmfZtukUGYLpYKJh697tqQmQ1Wf5G0zwstyP7IzYmBIXukUHT9FsMVysSQOXuo9elbsAIkUheKAwOV3AP2rLF3FWaSGvfSaxB8s7FlkvEin79M9MLTLPxJ\n---\njBzhce3GBpIYSJ3t1RxAQRoQvt10WLHXKr38EhUqfgXBQbLqWpEIQjdyGaoorhI8SbdAM9A0uPtPCXYXxc4yIx3A3CE70AMbr5usHriYiODQQU4TwYx9JjhIqDINvepTSpvuuBJlMnTBvn8hOWkKjX92lRJZeTItXGWEblEwHnQAYULTEl3nL1EZYQXNysL4VLqu1X14ZqTPa3VE0UcXqbkzAueKTYN04BACUY6s3IkcFqp0GXfUBg1WkyIV0fTGgGIKbTq7zYB4Ee9PNs0TrFmXWDkYVGsE97UgblxiBsKhf17Lo4108gNP3u2d5KpTCoybBtqcDiX54mJ8xM6wlCrsyRRi0dmqlRWgM2kPx7l2bnc8azlud4VtpKGNTfaKsNARF8STwzK5kHTS8MQsp4haAgC\n---\nvgM35mjv86JxjLm3AYO0WwN1AjEr3O98ej5SwAazswCNv9QxjzWFAT6oQujfjxr1LpIuwvLLNRPyYAm6NJ2uOKtHTeu2QhbMJkSxN0vQj8n0tAOGp7T6ZG6KqQ9Wsxo7ah2jlQIAhnA1vucdWAAiD950gTohdwG7Z8C720IA982D21CM6A5iswMoaig5lEranj5ooDv5FQhDzDXw3zQOXaXqlQInaSk6wzlA6cInccPcSlSl8y2NxIto7qBjgYvQFm2D6DsDkATquR7x9LdDpmfEd4eOrycMw0hHjVoXN0uRUXiRzrDa05zXwrBzOXbzfAlXUMk5k9X9xYdwu0gkDwj4qfzzJyI1hkxkvqVlpVRsPqHzFX6vmeh2ieMnKnsuG2HfshWy7WOF3GObLQr51npw8dph8R9FV8lLtqmc5MEY6kL5sDWsMvssEp1FaFGNBeGd1vvUSfeaJ1qV2etZ3cqOmMLqNofiO03m6o3romIF09EINC8aRcZVn4UrzgHqYMpJeHxOFYIUJR98k0Q2uTFU3rz1bIVyzHjVKaVEFYSawCxRDVD0x7Gq6lg3XlsUx8gKiMKAeGYZ7xquumiTAfCB8o1olFP3ca7T5D4PubpRyANtzYFp9cgUkqYqmpIizTXKv1kmPxqZmflnyDTBDByTsT04YLiVnzUQMXUD9qTSYcuoRORSfftnwML1lXaRDcRRy1J2hYopGZp8aQYrFpM5QNSh0MSZ960WhyARGYbH4kR2FCg6epq0kTSZhbE5D11wE8a14UM0B3ui0BVWLQ1529yHcvzfJCSTLvNw7ZnEcey972t7g7YNgsT4m3BF9erwMPcKW7Fiblz0hFW1lXI7HDmcnKGJJHKinvuRNit9c7qKCfEDzvj5nQ8f34qZKcfsPO4NePMNKvuz4ySEN0G2TSX6D1RzG9NzAs4PLHUF1XkQdVpF4Lbiz6xTZdp1iuFWT4PtTetulP1qWozDi6OBeMDp','26\n---\n26\n---\n26','personal','','','#include <iostream>\n#include <string>\n#include <unordered_set>\n#include <algorithm>\n\nusing namespace std;\n\nclass Solution {\npublic:\n    int lengthOfLongestSubstring(string s) {\n        unordered_set<char> occ;\n        int n = s.size();\n        int rk = -1, ans = 0;\n        for (int i = 0; i < n; ++i) {\n            if (i != 0) {\n                occ.erase(s[i - 1]);\n            }\n            while (rk + 1 < n && !occ.count(s[rk + 1])) {\n                occ.insert(s[rk + 1]);\n                ++rk;\n            }\n            ans = max(ans, rk - i + 1);\n        }\n        return ans;\n    }\n};\n\nint main() {\n    Solution solution;\n    string s;\n    getline(cin, s);\n    int result = solution.lengthOfLongestSubstring(s);\n    cout << result << endl;\n    return 0;\n}','字符串s string 1 undefined mixed\nrounds 10','#include <iostream>\n#include <string>\n#include <unordered_set>\n#include <algorithm>\n\nusing namespace std;\n\nclass Solution {\npublic:\n    int lengthOfLongestSubstring(string s) {\n        unordered_set<char> occ;\n        int n = s.size();\n        int rk = -1, ans = 0;\n        for (int i = 0; i < n; ++i) {\n            if (i != 0) {\n                occ.erase(s[i - 1]);\n            }\n            while (rk + 1 < n && !occ.count(s[rk + 1])) {\n                occ.insert(s[rk + 1]);\n                ++rk;\n            }\n            ans = max(ans, rk - i + 1);\n        }\n        return ans;\n    }\n};\n\nint main() {\n    Solution solution;\n    string s;\n    getline(cin, s);\n    int result = solution.lengthOfLongestSubstring(s);\n    cout << result << endl;\n    return 0;\n}','cpp'),

(2,'两数之和','给定一个整数数组 nums 和一个整数目标值 target，请你在该数组中找出和为目标值 target 的那两个整数，并返回它们的数组下标。','整数数组nums和目标值target','整数数组','["[2,7,11,15]", "[3,2,4]"]','["[0,1]", "[1,2]"]','简单',1000,256,'你可以假设每种输入只会对应一个答案',0,0,'[2,7,11,15,9]---[3,2,4,6]---[3,3,6]','[0,1]---[1,2]---[0,1]','personal','','','class Solution {\npublic:\n    vector<int> twoSum(vector<int>& nums, int target) {\n        unordered_map<int, int> mp;\n        for(int i=0;i<nums.size();i++){\n            if(mp.count(target-nums[i])) return {mp[target-nums[i]], i};\n            mp[nums[i]]=i;\n        }\n        return {};\n    }\n};',NULL,'class Solution {\npublic:\n    vector<int> twoSum(vector<int>& nums, int target) {\n        unordered_map<int, int> mp;\n        for(int i=0;i<nums.size();i++){\n            if(mp.count(target-nums[i])) return {mp[target-nums[i]], i};\n            mp[nums[i]]=i;\n        }\n        return {};\n    }\n};','cpp'),

(3,'反转链表','给你单链表的头节点 head ，请你反转链表，并返回反转后的链表。','链表头节点head','链表头节点','["[1,2,3,4,5]", "[1,2]"]','["[5,4,3,2,1]", "[2,1]"]','简单',1000,128,'链表中节点的数目范围是 [0, 5000]',0,0,'[1,2,3,4,5]---[1,2]---[]','[5,4,3,2,1]---[2,1]---[]','personal','','','class Solution {\npublic:\n    ListNode* reverseList(ListNode* head) {\n        ListNode* prev = nullptr;\n        ListNode* curr = head;\n        while(curr) {\n            ListNode* next = curr->next;\n            curr->next = prev;\n            prev = curr;\n            curr = next;\n        }\n        return prev;\n    }\n};',NULL,'class Solution {\npublic:\n    ListNode* reverseList(ListNode* head) {\n        ListNode* prev = nullptr;\n        ListNode* curr = head;\n        while(curr) {\n            ListNode* next = curr->next;\n            curr->next = prev;\n            prev = curr;\n            curr = next;\n        }\n        return prev;\n    }\n};','cpp'),

(4,'合并两个有序链表','将两个升序链表合并为一个新的 升序 链表并返回。新链表是通过拼接给定的两个链表的所有节点组成的。','两个升序链表list1和list2','升序链表','["[1,2,4]", "[1,3,4]"]','["[1,1,2,3,4,4]"]','简单',1000,128,'两个链表的节点数目范围是 [0, 50]',0,0,'[1,2,4]---[1,3,4]---[]---[]---[5]---[1,2,4]','[1,1,2,3,4,4]---[]---[1,2,4]---[1,2,4]---[1,2,4,5]','personal','','','class Solution {\npublic:\n    ListNode* mergeTwoLists(ListNode* list1, ListNode* list2) {\n        if(!list1) return list2;\n        if(!list2) return list1;\n        if(list1->val < list2->val) {\n            list1->next = mergeTwoLists(list1->next, list2);\n            return list1;\n        }\n        list2->next = mergeTwoLists(list1, list2->next);\n        return list2;\n    }\n};',NULL,'class Solution {\npublic:\n    ListNode* mergeTwoLists(ListNode* list1, ListNode* list2) {\n        if(!list1) return list2;\n        if(!list2) return list1;\n        if(list1->val < list2->val) {\n            list1->next = mergeTwoLists(list1->next, list2);\n            return list1;\n        }\n        list2->next = mergeTwoLists(list1, list2->next);\n        return list2;\n    }\n};','cpp'),

(5,'最大子数组和','给你一个整数数组 nums ，请你找出一个具有最大和的连续子数组（子数组最少包含一个元素），返回其最大和。','整数数组nums','整数','["[-2,1,-3,4,-1,2,1,-5,4]", "[1]", "[5,4,-1,7,8]"]','["6", "1", "23"]','中等',1000,128,'1 <= nums.length <= 10^5',0,0,'[-2,1,-3,4,-1,2,1,-5,4]---[1]---[5,4,-1,7,8]---[-2,-1]---[-1,-2]','6---1---23----1----1','personal','','','class Solution {\npublic:\n    int maxSubArray(vector<int>& nums) {\n        int ans = nums[0], cur = 0;\n        for(int x : nums) {\n            cur = max(x, cur + x);\n            ans = max(ans, cur);\n        }\n        return ans;\n    }\n};',NULL,'class Solution {\npublic:\n    int maxSubArray(vector<int>& nums) {\n        int ans = nums[0], cur = 0;\n        for(int x : nums) {\n            cur = max(x, cur + x);\n            ans = max(ans, cur);\n        }\n        return ans;\n    }\n};','cpp'),

(6,'爬楼梯','假设你正在爬楼梯。需要 n 阶你才能到达楼顶。每次你可以爬 1 或 2 个台阶。你有多少种不同的方法可以爬到楼顶呢？','整数n','整数','["2", "3", "4", "5", "1", "10"]','["2", "3", "5", "8", "1", "89"]','简单',1000,128,'1 <= n <= 45',0,0,'2---3---4---5---1---10---45---30---20---15','2---3---5---8---1---89---1836311903---1346269---10946---987','personal','','','class Solution {\npublic:\n    int climbStairs(int n) {\n        if(n <= 2) return n;\n        int a=1,b=2,c;\n        for(int i=3;i<=n;i++){\n            c=a+b;\n            a=b;\n            b=c;\n        }\n        return b;\n    }\n};',NULL,'class Solution {\npublic:\n    int climbStairs(int n) {\n        if(n <= 2) return n;\n        int a=1,b=2,c;\n        for(int i=3;i<=n;i++){\n            c=a+b;\n            a=b;\n            b=c;\n        }\n        return b;\n    }\n};','cpp'),

(7,'二叉树的中序遍历','给定一个二叉树的根节点 root ，返回它的中序遍历。','二叉树根节点root','整数数组','["[1,null,2,3]", "[]"]','["[1,3,2]", "[]"]','简单',1000,128,'树中节点数目在范围 [0, 100] 内',0,0,'[1,null,2,3]---[]---[1]---[1,2]---[1,2,3,4,5]','[1,3,2]---[]---[1]---[2,1]---[4,2,5,1,3]','personal','','','class Solution {\npublic:\n    vector<int> inorderTraversal(TreeNode* root) {\n        vector<int> res;\n        stack<TreeNode*> st;\n        while(root || !st.empty()) {\n            while(root) {\n                st.push(root);\n                root = root->left;\n            }\n            root = st.top();\n            st.pop();\n            res.push_back(root->val);\n            root = root->right;\n        }\n        return res;\n    }\n};',NULL,'class Solution {\npublic:\n    vector<int> inorderTraversal(TreeNode* root) {\n        vector<int> res;\n        stack<TreeNode*> st;\n        while(root || !st.empty()) {\n            while(root) {\n                st.push(root);\n                root = root->left;\n            }\n            root = st.top();\n            st.pop();\n            res.push_back(root->val);\n            root = root->right;\n        }\n        return res;\n    }\n};','cpp'),

(8,'对称二叉树','给你一个二叉树的根节点 root ，检查它是否轴对称。','二叉树根节点root','布尔值','["[1,2,2,3,4,4,3]", "[1,2,2,null,3,null,3]"]','["true", "false"]','简单',1000,128,'树中节点数目在范围 [1, 1000] 内',0,0,'[1,2,2,3,4,4,3]---[1,2,2,null,3,null,3]---[1,2,2,3,4,3,4]---[1]','true---false---false---true','personal','','','class Solution {\npublic:\n    bool isSymmetric(TreeNode* root) {\n        return isMirror(root, root);\n    }\n    bool isMirror(TreeNode* t1, TreeNode* t2) {\n        if(!t1 && !t2) return true;\n        if(!t1 || !t2) return false;\n        return t1->val == t2->val && isMirror(t1->left, t2->right) && isMirror(t1->right, t2->left);\n    }\n};',NULL,'class Solution {\npublic:\n    bool isSymmetric(TreeNode* root) {\n        return isMirror(root, root);\n    }\n    bool isMirror(TreeNode* t1, TreeNode* t2) {\n        if(!t1 && !t2) return true;\n        if(!t1 || !t2) return false;\n        return t1->val == t2->val && isMirror(t1->left, t2->right) && isMirror(t1->right, t2->left);\n    }\n};','cpp'),

(9,'二叉树的最大深度','给定一个二叉树 root ，返回其最大深度。二叉树的深度是指从根节点到最远叶子节点的最长路径上的节点数。','二叉树根节点root','整数','["[3,9,20,null,null,15,7]", "[1,null,2]", "[]"]','["3", "2", "0"]','简单',1000,128,'树中节点的数量在 [0, 10^4] 范围内',0,0,'[3,9,20,null,null,15,7]---[1,null,2]---[]---[1,2,3,4,5]---[1,2,3,4]','3---2---0---5---3','personal','','','class Solution {\npublic:\n    int maxDepth(TreeNode* root) {\n        if(!root) return 0;\n        return 1 + max(maxDepth(root->left), maxDepth(root->right));\n    }\n};',NULL,'class Solution {\npublic:\n    int maxDepth(TreeNode* root) {\n        if(!root) return 0;\n        return 1 + max(maxDepth(root->left), maxDepth(root->right));\n    }\n};','cpp'),

(10,'只出现一次的数字','给定一个非空整数数组，除了某个元素只出现一次以外，其余每个元素均出现两次。找出那个只出现了一次的元素。','整数数组nums','整数','["[2,2,1]", "[4,1,2,1,2]", "[1]"]','["1", "4", "1"]','简单',1000,128,'1 <= nums.length <= 3 * 10^4',0,0,'[2,2,1]---[4,1,2,1,2]---[1]---[2,2,1,1,3]---[5,3,4,5,4]','1---4---1---3---3','personal','','','class Solution {\npublic:\n    int singleNumber(vector<int>& nums) {\n        int ans = 0;\n        for(int x : nums) ans ^= x;\n        return ans;\n    }\n};',NULL,'class Solution {\npublic:\n    int singleNumber(vector<int>& nums) {\n        int ans = 0;\n        for(int x : nums) ans ^= x;\n        return ans;\n    }\n};','cpp'),

(11,'环形链表','给你一个链表的头节点 head ，判断链表中是否有环。','链表头节点head','布尔值','["[3,2,0,-4]", "[1,2]", "[1]"]','["true", "true", "false"]','简单',1000,128,'链表中节点的数目范围是 [0, 10^4]',0,0,'[3,2,0,-4]---[1,2]---[1]---[1,2,3,4,5]','true---true---false---false','personal','','','class Solution {\npublic:\n    bool hasCycle(ListNode* head) {\n        if(!head || !head->next) return false;\n        ListNode* slow = head;\n        ListNode* fast = head->next;\n        while(slow != fast) {\n            if(!fast || !fast->next) return false;\n            slow = slow->next;\n            fast = fast->next->next;\n        }\n        return true;\n    }\n};',NULL,'class Solution {\npublic:\n    bool hasCycle(ListNode* head) {\n        if(!head || !head->next) return false;\n        ListNode* slow = head;\n        ListNode* fast = head->next;\n        while(slow != fast) {\n            if(!fast || !fast->next) return false;\n            slow = slow->next;\n            fast = fast->next->next;\n        }\n        return true;\n    }\n};','cpp');



DROP TABLE IF EXISTS `problem_tag`;
CREATE TABLE `problem_tag` (
  `problem_id` bigint NOT NULL,
  `tag_id` bigint NOT NULL,
  PRIMARY KEY (`problem_id`,`tag_id`),
  KEY `idx_tag_id` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TABLE IF EXISTS `tag`;
CREATE TABLE `tag` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;



-- ═══════════════════════════════════════════════
-- 库 2：doj_submission（提交/判题服务）
-- ═══════════════════════════════════════════════
USE `doj_submission`;

DROP TABLE IF EXISTS `submission`;
CREATE TABLE `submission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '提交记录主键',
  `user_id` bigint NOT NULL COMMENT '用户ID（来源于 doj_user.user）',
  `problem_id` bigint NOT NULL COMMENT '题目ID（来源于 doj_problem.problem）',
  `user_name` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户名称',
  `problem_name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '题目名称',
  `language` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'cpp' COMMENT '编程语言',
  `code` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '提交的代码文本内容',
  `exit_value` int DEFAULT NULL COMMENT '程序退出码',
  `status` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '判题状态',
  `message` longtext COLLATE utf8mb4_unicode_ci,
  `time` double DEFAULT NULL COMMENT '运行时间（单位：秒）',
  `memory` bigint DEFAULT NULL COMMENT '内存使用（单位：KB）',
  `submit_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代码提交记录表';

-- ═══════════════════════════════════════════════
-- 库 3：doj_user（用户/公告服务）
-- ═══════════════════════════════════════════════
USE `doj_user`;

DROP TABLE IF EXISTS `announcement`;
CREATE TABLE `announcement` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(255) NOT NULL,
  `content` text,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `creator_id` bigint DEFAULT NULL,
  `deleted` tinyint(1) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `avatar` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `email` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `password` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `score` int DEFAULT NULL,
  `ranks` int DEFAULT NULL,
  `school` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `gender` tinyint(1) DEFAULT NULL,
  `easy_solve` int DEFAULT NULL,
  `middle_solve` int DEFAULT NULL,
  `hard_solve` int DEFAULT NULL,
  `role` tinyint(1) DEFAULT NULL,
  `url` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `sign` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `fans` bigint DEFAULT NULL,
  `subscribe` bigint DEFAULT NULL,
  `ban` tinyint(1) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

