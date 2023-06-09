package com.chen.medical.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chen.medical.common.DeleteRequest;
import com.chen.medical.common.ErrorCode;
import com.chen.medical.constant.CommonConstant;
import com.chen.medical.exception.BusinessException;
import com.chen.medical.exception.ThrowUtils;
import com.chen.medical.mapper.EssayFavourMapper;
import com.chen.medical.mapper.EssayThumbMapper;
import com.chen.medical.model.dto.essay.EssayEsDTO;
import com.chen.medical.model.entity.Essay;
import com.chen.medical.model.dto.essay.EssayAddRequest;
import com.chen.medical.model.dto.essay.EssayQueryRequest;
import com.chen.medical.model.entity.EssayFavour;
import com.chen.medical.model.entity.EssayThumb;
import com.chen.medical.model.entity.User;
import com.chen.medical.model.vo.EssayVO;
import com.chen.medical.service.UserService;
import com.chen.medical.utils.SqlUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import com.chen.medical.service.EssayService;
import com.chen.medical.mapper.EssayMapper;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author chenjiahan
 * @description 针对表【essay(文章)】的数据库操作Service实现
 * @createDate 2023-04-05 09:59:22
 */
@Service
@Slf4j
public class EssayServiceImpl extends ServiceImpl<EssayMapper, Essay>
        implements EssayService {

    @Resource
    private ElasticsearchRestTemplate elasticsearchRestTemplate;

    @Resource
    private UserService userService;

    @Resource
    private EssayFavourMapper essayFavourMapper;

    @Resource
    private EssayThumbMapper essayThumbMapper;

    @Override
    public Long addEssay(EssayAddRequest essayAddRequest) {
        if (essayAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Essay essay = new Essay();
        BeanUtils.copyProperties(essayAddRequest, essay);
        this.validEssay(essay, true);
        boolean result = this.save(essay);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);

        return essay.getId();
    }

    @Override
    public Boolean deleteEssay(DeleteRequest deleteRequest) {
        Long id = deleteRequest.getId();
        Essay oldEssay = this.getById(id);
        ThrowUtils.throwIf(oldEssay == null, ErrorCode.NOT_FOUND_ERROR);
        // 删除db中的数据
        boolean result = this.removeById(id);
        String delete = elasticsearchRestTemplate.delete(String.valueOf(id), EssayEsDTO.class);
        log.info("delete essay {} from ElasticSearch", delete);
        return result;
    }

    @Override
    public void validEssay(Essay essay, boolean add) {
        if (essay == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String author = essay.getAuthor();
        String title = essay.getTitle();
        String content = essay.getContent();
        String tags = essay.getTags();
        // 创建时，参数不能为空
        if (add) {
            ThrowUtils.throwIf(StringUtils.isAnyBlank(title, content, tags), ErrorCode.PARAMS_ERROR);
        }
        // 有参数则校验
        if (StringUtils.isNotBlank(title) && title.length() > 80) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "标题过长");
        }
        if (StringUtils.isNotBlank(content) && content.length() > 8192) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "简介过长");
        }
        if (StringUtils.isNotBlank(author) && author.length() > 80) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "名称过长");
        }
    }

    @Override
    public Page<EssayVO> getEssayVOPage(Page<Essay> essayPage, HttpServletRequest request) {
        List<Essay> essayList = essayPage.getRecords();
        Page<EssayVO> essayVOPage = new Page<>(essayPage.getCurrent(), essayPage.getSize(), essayPage.getTotal());
        if (CollectionUtils.isEmpty(essayList)) {
            return essayVOPage;
        }
        // 1. 关联查询用户信息
        Set<Long> userIdSet = essayList.stream().map(Essay::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 2. 已登录，获取用户点赞、收藏状态
        Map<Long, Boolean> essayIdHasThumbMap = new HashMap<>();
        Map<Long, Boolean> essayIdHasFavourMap = new HashMap<>();
        User loginUser = userService.getLoginUserPermitNull(request);
        if (loginUser != null) {
            Set<Long> essayIdSet = essayList.stream().map(Essay::getId).collect(Collectors.toSet());
            loginUser = userService.getLoginUser(request);
            // 获取点赞
            QueryWrapper<EssayThumb> essayThumbQueryWrapper = new QueryWrapper<>();
            essayThumbQueryWrapper.in("essayId", essayIdSet);
            essayThumbQueryWrapper.eq("userId", loginUser.getId());
            List<EssayThumb> essayEssayThumbList = essayThumbMapper.selectList(essayThumbQueryWrapper);
            essayEssayThumbList.forEach(essayEssayThumb -> essayIdHasThumbMap.put(essayEssayThumb.getEssayId(), true));
            // 获取收藏
            QueryWrapper<EssayFavour> essayFavourQueryWrapper = new QueryWrapper<>();
            essayFavourQueryWrapper.in("essayId", essayIdSet);
            essayFavourQueryWrapper.eq("userId", loginUser.getId());
            List<EssayFavour> essayFavourList = essayFavourMapper.selectList(essayFavourQueryWrapper);
            essayFavourList.forEach(essayFavour -> essayIdHasFavourMap.put(essayFavour.getEssayId(), true));
        }
        // 填充信息
        List<EssayVO> essayVOList = essayList.stream().map(essay -> {
            EssayVO essayVO = EssayVO.objToVo(essay);
            Long userId = essay.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            essayVO.setUser(userService.getUserVO(user));
            essayVO.setHasThumb(essayIdHasThumbMap.getOrDefault(essay.getId(), false));
            essayVO.setHasFavour(essayIdHasFavourMap.getOrDefault(essay.getId(), false));
            return essayVO;
        }).collect(Collectors.toList());
        essayVOPage.setRecords(essayVOList);
        return essayVOPage;
    }

    @Override
    public QueryWrapper<Essay> getQueryWrapper(EssayQueryRequest essayQueryRequest) {
        QueryWrapper<Essay> queryWrapper = new QueryWrapper<>();
        if (essayQueryRequest == null) {
            return queryWrapper;
        }
        String searchText = essayQueryRequest.getSearchText();
        String sortField = essayQueryRequest.getSortField();
        String sortOrder = essayQueryRequest.getSortOrder();
        Long id = essayQueryRequest.getId();
        String title = essayQueryRequest.getTitle();
        String content = essayQueryRequest.getContent();
        List<String> tagList = essayQueryRequest.getTags();
        Long userId = essayQueryRequest.getUserId();
        Long notId = essayQueryRequest.getNotId();
        // 拼接查询条件
        if (StringUtils.isNotBlank(searchText)) {
            queryWrapper.like("title", searchText).or().like("content", searchText);
        }
        queryWrapper.like(StringUtils.isNotBlank(title), "title", title);
        queryWrapper.like(StringUtils.isNotBlank(content), "content", content);
        if (CollectionUtils.isNotEmpty(tagList)) {
            for (String tag : tagList) {
                queryWrapper.like("tags", "\"" + tag + "\"");
            }
        }
        queryWrapper.ne(ObjectUtils.isNotEmpty(notId), "id", notId);
        queryWrapper.eq(ObjectUtils.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq("isDelete", false);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    @Override
    public Page<EssayEsDTO> searchFromEs(EssayQueryRequest essayQueryRequest) {
        Long id = essayQueryRequest.getId();
        String searchText = essayQueryRequest.getSearchText();
        String title = essayQueryRequest.getTitle();
        String author = essayQueryRequest.getAuthor();
        String content = essayQueryRequest.getContent();
        List<String> tagList = essayQueryRequest.getTags();
        List<String> orTagList = essayQueryRequest.getOrTags();
        // es 起始页为 0
        long current = essayQueryRequest.getCurrent() - 1;
        long pageSize = essayQueryRequest.getPageSize();
        String sortField = essayQueryRequest.getSortField();
        String sortOrder = essayQueryRequest.getSortOrder();
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
        // 过滤
        boolQueryBuilder.filter(QueryBuilders.termQuery("isDelete", 0));
        if (id != null) {
            boolQueryBuilder.filter(QueryBuilders.termQuery("id", id));
        }
        // 必须包含所有标签
        if (CollectionUtils.isNotEmpty(tagList)) {
            for (String tag : tagList) {
                boolQueryBuilder.filter(QueryBuilders.termQuery("tags", tag));
            }
        }
        // 包含任何一个标签即可
        if (CollectionUtils.isNotEmpty(orTagList)) {
            BoolQueryBuilder orTagBoolQueryBuilder = QueryBuilders.boolQuery();
            for (String tag : orTagList) {
                orTagBoolQueryBuilder.should(QueryBuilders.termQuery("tags", tag));
            }
            orTagBoolQueryBuilder.minimumShouldMatch(1);
            boolQueryBuilder.filter(orTagBoolQueryBuilder);
        }
        // 按关键词检索
        if (StringUtils.isNotBlank(searchText)) {
            boolQueryBuilder.should(QueryBuilders.matchQuery("author", searchText));
            boolQueryBuilder.should(QueryBuilders.matchQuery("title", searchText));
            boolQueryBuilder.should(QueryBuilders.matchQuery("content", searchText));
            boolQueryBuilder.should(QueryBuilders.matchQuery("article", searchText));
            boolQueryBuilder.minimumShouldMatch(1);
        }
        // 根据标题搜索
        if (StringUtils.isNotBlank(title)) {
            boolQueryBuilder.should(QueryBuilders.matchQuery("title", title));
            boolQueryBuilder.minimumShouldMatch(1);
        }
        // 根据作者搜索
        if (StringUtils.isNotBlank(author)) {
            boolQueryBuilder.should(QueryBuilders.matchQuery("author", author));
            boolQueryBuilder.minimumShouldMatch(1);
        }
        // 根据梗要搜索
        if (StringUtils.isNotBlank(content)) {
            boolQueryBuilder.should(QueryBuilders.matchQuery("content", content));
            boolQueryBuilder.minimumShouldMatch(1);
        }
        // 排序
        SortBuilder<?> sortBuilder = SortBuilders.scoreSort();
        if (StringUtils.isNotBlank(sortField)) {
            sortBuilder = SortBuilders.fieldSort(sortField);
            sortBuilder.order(CommonConstant.SORT_ORDER_ASC.equals(sortOrder) ? SortOrder.ASC : SortOrder.DESC);
        }
        // 分页
        PageRequest pageRequest = PageRequest.of((int) current, (int) pageSize);
        // 构造查询
        NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
                .withQuery(boolQueryBuilder)
                .withHighlightFields(
                        new HighlightBuilder.Field("content")
                        , new HighlightBuilder.Field("title")
                        , new HighlightBuilder.Field("author")
                )
                .withHighlightBuilder(new HighlightBuilder()
                        .preTags("<span style='color: red;'>")
                        .postTags("</em>"))
                .withPageable(pageRequest).withSorts(sortBuilder)
                .build();
        SearchHits<EssayEsDTO> searchHits = elasticsearchRestTemplate.search(searchQuery, EssayEsDTO.class);
        Page<EssayEsDTO> page = new Page<>();
        page.setTotal(searchHits.getTotalHits());
        List<EssayEsDTO> resourceList = new ArrayList<>();
        // 查出结果后，从 db 获取最新动态数据
        if (searchHits.hasSearchHits()) {
            List<SearchHit<EssayEsDTO>> searchHitList = searchHits.getSearchHits();
            List<Long> essayIdList = searchHitList.stream().map(searchHit -> searchHit.getContent().getId())
                    .collect(Collectors.toList());
            for (SearchHit<EssayEsDTO> searchHit : searchHits) {
                //高亮的内容
                Map<String, List<String>> highlightsFields = searchHit.getHighlightFields();
                //将高亮的内容填充到content中
                searchHit.getContent().setContent(highlightsFields.get("content") == null ? searchHit.getContent().getContent() : highlightsFields.get("content").get(0));
                searchHit.getContent().setTitle(highlightsFields.get("title") == null ? searchHit.getContent().getTitle() : highlightsFields.get("title").get(0));
                searchHit.getContent().setAuthor(highlightsFields.get("author") == null ? searchHit.getContent().getAuthor() : highlightsFields.get("author").get(0));
                // 放到实体类中
                resourceList.add(searchHit.getContent());
            }
//            // 从数据库中取出更完整的数据
//            List<Essay> essayList = baseMapper.selectBatchIds(essayIdList);
//            if (essayList != null) {
//                Map<Long, List<Essay>> idEssayMap = essayList.stream().collect(Collectors.groupingBy(Essay::getId));
//                essayIdList.forEach(essayId -> {
//                    if (idEssayMap.containsKey(essayId)) {
//                        resourceList.add(idEssayMap.get(essayId).get(0));
//                    } else {
//                        // 从 es 清空 db 已物理删除的数据
//                        String delete = elasticsearchRestTemplate.delete(String.valueOf(essayId), EssayEsDTO.class);
//                        log.info("delete essay {}", delete);
//                    }
//                });
//            }
        }
        page.setRecords(resourceList);
        return page;
    }
}




