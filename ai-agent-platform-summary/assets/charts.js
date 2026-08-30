(function() {
  var style = getComputedStyle(document.documentElement);
  var accent = style.getPropertyValue('--accent').trim();
  var accent2 = style.getPropertyValue('--accent2').trim();
  var accent3 = style.getPropertyValue('--accent3').trim();
  var ink = style.getPropertyValue('--ink').trim();
  var muted = style.getPropertyValue('--muted').trim();
  var rule = style.getPropertyValue('--rule').trim();
  var bg2 = style.getPropertyValue('--bg2').trim();
  var success = style.getPropertyValue('--success').trim();
  var warning = style.getPropertyValue('--warning').trim();
  var pending = style.getPropertyValue('--pending').trim();

  // --- Chart: Module Completion ---
  var chartCompletion = echarts.init(document.getElementById('chart-completion'), null, { renderer: 'svg' });

  var modules = [
    { name: 'Core 核心框架', value: 90, status: 'done' },
    { name: 'Memory 记忆系统', value: 85, status: 'done' },
    { name: 'LLM 抽象层', value: 40, status: 'partial' },
    { name: 'WebSocket 实时游戏', value: 95, status: 'done' },
    { name: '谁是卧底游戏(REST)', value: 90, status: 'done' },
    { name: 'REST API 接口层', value: 80, status: 'partial' },
    { name: 'Tool 工具调用', value: 5, status: 'todo' },
    { name: '前端页面', value: 5, status: 'todo' },
    { name: 'Config 配置层', value: 75, status: 'partial' }
  ];

  var barColors = modules.map(function(m) {
    if (m.status === 'done') return success;
    if (m.status === 'partial') return warning;
    return pending;
  });

  chartCompletion.setOption({
    animation: false,
    tooltip: {
      trigger: 'axis',
      appendToBody: true,
      axisPointer: { type: 'shadow' },
      formatter: function(params) {
        var data = params[0];
        var statusText = '';
        if (data.value >= 80) statusText = '已完成';
        else if (data.value >= 30) statusText = '部分完成';
        else statusText = '待开发';
        return data.name + '<br/>完成度: ' + data.value + '% (' + statusText + ')';
      },
      backgroundColor: bg2,
      borderColor: rule,
      textStyle: { color: ink }
    },
    grid: {
      left: '3%',
      right: '12%',
      bottom: '3%',
      top: '3%',
      containLabel: true
    },
    xAxis: {
      type: 'value',
      max: 100,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: muted,
        formatter: '{value}%'
      },
      splitLine: {
        lineStyle: { color: rule, type: 'dashed' }
      }
    },
    yAxis: {
      type: 'category',
      data: modules.map(function(m) { return m.name; }),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: ink,
        fontSize: 12
      }
    },
    series: [{
      type: 'bar',
      data: modules.map(function(m, i) {
        return {
          value: m.value,
          itemStyle: {
            color: barColors[i],
            borderRadius: [0, 4, 4, 0]
          }
        };
      }),
      barWidth: 16,
      label: {
        show: true,
        position: 'right',
        color: muted,
        fontSize: 11,
        formatter: '{c}%'
      }
    }]
  });

  window.addEventListener('resize', function() {
    chartCompletion.resize();
  });
})();
